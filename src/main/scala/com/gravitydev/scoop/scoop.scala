package com.gravitydev.scoop

import java.sql.{ResultSet, PreparedStatement, Types, Timestamp, Date}
import util.Logging

object `package` {
  type Table[T <: ast.SqlTable[T]] = ast.SqlTable[T]
  type TableCompanion[T <: Table[T]] = {def apply(s: String, pf: String): T}

  def opt [T](p: ResultSetParser[T]): ResultSetParser[Option[T]] = new ResultSetParser [Option[T]] {
    def columns = p.columns
    def apply (rs: ResultSet) = p(rs) match {
      case Success(s) => Success(Option(s))
      case Failure(e) => Success(None)
    }
  }

  implicit object int        extends SqlNativeType [Int]          (Types.INTEGER,   _ getInt _,       _ setInt (_,_))  
  implicit object long       extends SqlNativeType [Long]         (Types.BIGINT,    _ getLong _,      _ setLong (_,_))
  implicit object double     extends SqlNativeType [Double]       (Types.DOUBLE,    _ getDouble _,    _ setDouble (_,_))
  implicit object string     extends SqlNativeType [String]       (Types.VARCHAR,   _ getString _,    _ setString (_,_))
  implicit object timestamp  extends SqlNativeType [Timestamp]    (Types.TIMESTAMP, _ getTimestamp _, _ setTimestamp (_,_))
  implicit object date       extends SqlNativeType [Date]         (Types.DATE,      _ getDate _,      _ setDate (_,_))
  implicit object boolean    extends SqlNativeType [Boolean]      (Types.BOOLEAN,   _ getBoolean _,   _ setBoolean (_,_))
  implicit object decimal    extends SqlNativeType [scala.math.BigDecimal] (Types.DECIMAL, (rs, idx) => scala.math.BigDecimal(rs.getBigDecimal(idx)), (rs, idx, value) => rs.setBigDecimal(idx, value.underlying))
  
  implicit def toColumnParser [X](c: ast.SqlNonNullableCol[X]) = new ColumnParser(c)
  implicit def toNullableColumnParser [X](c: ast.SqlNullableCol[X]) = new NullableColumnParser(c)
  implicit def toColumnWrapper [X](c: ast.SqlCol[X]) = ColumnWrapper(c)
  
  private[scoop] def renderParams (params: Seq[SqlParam[_]]) = params.map(x => x.v + ":"+x.v.asInstanceOf[AnyRef].getClass.getName.stripPrefix("java.lang."))
}

private [scoop] sealed trait ParseResult[+T] {
  def map [X](fn: T => X): ParseResult[X] = this match {
    case Success(v) => Success(fn(v))
    case Failure(e) => Failure(e)
  }
  def flatMap [X](fn: T => ParseResult[X]): ParseResult[X] = fold (
    e => Failure(e),
    v => fn(v)
  )
  def fold [X](lf: String => X, rf: T => X) = this match {
    case Failure(e) => lf(e)
    case Success(v) => rf(v)
  }
  def get = fold (
    e => error(e),
    v => v
  )
}
private [scoop] case class Success [T] (v: T) extends ParseResult[T]
private [scoop] case class Failure (error: String) extends ParseResult[Nothing]

trait SqlType [T] {self =>
  def tpe: Int // jdbc sql type
  def set (stmt: PreparedStatement, idx: Int, value: T): Unit
  def parse (rs: ResultSet, name: String): Option[T]
  def apply (n: String, sql: String = "") = new ExprParser (n, this, sql)
}
  
abstract class SqlNativeType[T] (val tpe: Int, get: (ResultSet, String) => T, _set: (PreparedStatement, Int, T) => Unit) extends SqlType [T] with Logging {
  def set (stmt: PreparedStatement, idx: Int, value: T): Unit = {
    if (value==null) stmt.setNull(idx, tpe)
    else _set(stmt, idx, value)
  }
  def parse (rs: ResultSet, name: String) = Option(get(rs, name)) filter {_ => !rs.wasNull}
}
abstract class SqlCustomType[T,N] (from: N => T, to: T => N)(implicit nt: SqlNativeType[N]) extends SqlType[T] {
  def tpe = nt.tpe
  def parse (rs: ResultSet, name: String) = nt.parse(rs, name) map from
  def set (stmt: PreparedStatement, idx: Int, value: T): Unit = nt.set(stmt, idx, to(value))
}

sealed trait SqlParam [T] {
  val v: T
  def apply (stmt: PreparedStatement, idx: Int): Unit
}

case class SqlSingleParam [T,S] (v: T)(implicit val tp: SqlType[T]) extends SqlParam[T] {
  def apply (stmt: PreparedStatement, idx: Int) = tp.set(stmt, idx, v)
}
case class SqlSetParam [T](v: Set[T])(implicit tp: SqlType[T]) extends SqlParam[Set[T]] {
  def toList = v.toList.map(x => SqlSingleParam(x))
  def apply (stmt: PreparedStatement, idx: Int) = error("WTF!")
}

trait ResultSetParser[T] extends (ResultSet => ParseResult[T]) {self =>
  def map [X] (fn: T => X): ResultSetParser[X] = new ResultSetParser [X] {
    def apply (rs: ResultSet) = self(rs) map fn
    def columns = self.columns
  }
  def flatMap [X] (fn: T => ResultSetParser[X]): ResultSetParser[X] = new ResultSetParser [X] {
    def apply (rs: ResultSet) = for (x <- self(rs); y <- fn(x)(rs)) yield y
    def columns = self.columns 
  }
  
  def columns: List[query.ExprS]
}

case class literal [T] (value: T) extends ResultSetParser [T] {
  def columns = Nil
  def apply (rs: ResultSet) = Success(value)
}

class ExprParser [T] (name: String, exp: SqlType[T], sql: String = "") 
    extends boilerplate.ParserBase[T] (exp.parse(_, name) map {Success(_)} getOrElse Failure("Could not parse expression: " + name)) {
  def prefix (pf: String) = new ExprParser (pf+name, exp)
  def columns = List(sql) filter (_!="") map (x => x+" as "+name: query.ExprS)
}

class ColumnParser[T](column: ast.SqlNonNullableCol[T]) 
    extends boilerplate.ParserBase[T] (rs => 
      column parse rs map {Success(_)} getOrElse {
        Failure("Could not parse column [" + column.name + "] from " + util.inspectRS(rs))
      }
    ) {
  def name = column.name
  def columns = List(column.selectSql)
}

class NullableColumnParser[T](column: ast.SqlNullableCol[T]) 
    extends boilerplate.ParserBase[Option[T]] (rs => 
      column parse rs map {Success(_)} getOrElse {
        Failure("Could not parse [" + column.name + "] (optional) from " + util.inspectRS(rs)) 
      }
    ) {
  def name = column.name
  def columns = List(column.selectSql)
}

abstract class SqlOrder (val sql: String)
case object Ascending   extends SqlOrder ("ASC")
case object Descending  extends SqlOrder ("DESC")

case class SqlOrdering (col: ast.SqlCol[_], order: SqlOrder) {
  def sql = col.sql + " " + order.sql
}

case class ColumnWrapper [X](col: ast.SqlCol[X]) {
  def desc  = SqlOrdering(col, Descending)
  def asc   = SqlOrdering(col, Ascending)
}

