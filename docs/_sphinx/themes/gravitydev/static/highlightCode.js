jQuery(document).ready(function($) {
    if (typeof disableStyleCode != "undefined") {
        return;
    }
    var a = false;
    $("pre").each(function() {      
        if (!$(this).hasClass("prettyprint")) {
            $(this).addClass("prettyprint lang-scala");
            a = true
        }
    });
    if (a) { prettyPrint() } 
});
