Usage
=====

To use it, just import the library, the query API and your datamodel like this:

```scala
import com.gravitydev.scoop._, query._
import my.project.sql._
...
```

Connection
----------

Scoop does nothing special for managing a connection. You should use your own connection pool or whatever the framework you're using provides. 
Scoop only requires that you give it a java.sql.Connection. It will take one implicitly if you define one as such. Here is an example for the Play! framework:
```scala
...
DB.withConnection {implicit conn =>
  // scoop stuff
  from(users).where(users.id === 1).select(users.first_name) map {u => 
    // some code
  }
}
...
```

Keep in mind that you only really need the connection when you execute your query by calling the map method:
```scala
val q = from(users).select(users.*)

DB.withConnection {implicit conn =>
  q map {u => println(u)}
}
```

Queries
-------

### Basic DSL ###

Most queries start by using the "from" method and continue building the query using a "fluent-interface" style:

SELECT * FROM users:
```scala
from(tables.users) // produces a Query object that can be executed or extended later
```

SELECT issues.title 
FROM issues 
WHERE issues.id = 1 
  AND issues.created_at < '2012-12-30 12:31:10' -- assuming that's yesterday
ORDER BY issues.created_at DESC
LIMIT 20
```scala
from(tables.issues)
  .where(issues.id === 1 and issues.created_at < yesterday) // assuming "yesterday" is a java.sql.Date
  .select(i.title)
  .orderBy(i.created_at desc)
  .limit(20)
```

You can also use a custom string in most places:
```scala
from(tables.users).select(users.id, "COUNT(*) as total")
```

### Aliasing ####

When making queries you typically want to create aliases for the tables you are using, and you don't want those aliases to conflict with those of other tables.
You can use the "using" method for this:

```scala
using (tables.issues) {i => // "i" now represents the issues table, but with a unique alias
  from(i).where(i.id === 1).select(i.title)
}

// aliasing multiple tables
using (tables.issues, tables.projects) {(i,p) =>
  from(i)
    .innerJoin(p on p.project_id === p.id)
    .where(i.id === 1)
    .select(i.title, i.description)
}

// self-join with unique aliases 
// (keep in mind aliases generated won't necessarly match the identifiers used for the function params)
using (tables.issues, tables.issues) {(i,dup) => 
  from(i)
    .leftJoin(dup on i.duplicate_of === dup.id)
    .where(i.id === 1)
    .select(i.title, i.description, dup.id)
}
```

Processing the Result
---------------------

A "Query" object has a map method that can be used to execute the query and do something with the result. It takes a function of type ResultSet => T, where T can be anything:

```scala
val q = from(users)

val ids: List[Long] = q map {rs => // jdbc ResultSet
  rs.getLong("id")
}
```

Most often you won't need to deal with the ResultSet directly and will use parsers instead. Parsers are functions of type ResultSet => T
```scala
// "long" is a basic parser included with scoop
val ids: List[Long] = q map long("id")

// column definitions are also parsers of their respective types
val ids: List[Long] = q map users.id

// you can combine multiple parsers
val data: List[(Long, String, Option[String])] = q map (users.id ~ users.first_name ~ users.nickname)

// you can also define parsers and map their results to something else
using (tables.users) {u =>
  val parser = u.id ~ u.first_name ~ u.last_name ~ u.nickname >> {(id,first,last,nickname) = new User(id, first, last, nickname)}
  
  val users: List[User] = q map parser
}

// if you use case classes, it is even easier to define ther parsers by using the "apply" method on their companion object
using (tables.users) {u =>
  val parser = u.id ~ u.first_name ~ u.last_name ~ u.nickname >> User.apply
  
  val users: List[User] = q map parser
}

// you can define common reusable parsers somewhere else
// they will be portable as long as they are built for the specific aliases from the query
// to do this, simply take table references as parameters when creating the parser
case class User(id: Long, first: String, last: String, nickname: Option[String])
case class Project (id: String, name: String, creator: User)

object Parsers {
  def users (u: tables.users) = u.id ~ u.first_name ~ u.last_name ~ u.nickname >> User.apply
  def project (p: tables.project, u: tables.users) = p.id ~ p.name ~ users(u) >> Project.apply
}

using (tables.projects, tables.users) {(p,u) =>
  val parser = Parsers.project(p,u) // instantiate a parser made for the specific aliases that we are using
  
  val projectsWithCreators: List[Project] = q map parser
}
```

