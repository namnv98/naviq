grammar Demo;

@header {
package com.naviq.antlr4.postgresql;
}

select_stmt
    : SELECT columnref (COMMA columnref)* FROM qualified_name (WHERE bool_expr)? EOF
    ;

columnref
    : STAR
    | IDENTIFIER
    ;

qualified_name
    : IDENTIFIER (DOT IDENTIFIER)?
    ;

bool_expr
    : columnref comparison_op value
    ;

comparison_op
    : EQ | NEQ | LE | GE | LT | GT
    ;

value
    : IDENTIFIER | NUMBER | STRING
    ;

SELECT : 'SELECT' ;
FROM   : 'FROM' ;
WHERE  : 'WHERE' ;
COMMA  : ',' ;
DOT    : '.' ;
STAR   : '*' ;
EQ     : '=' ;
NEQ    : '!=' | '<>' ;
LE     : '<=' ;
GE     : '>=' ;
LT     : '<' ;
GT     : '>' ;
IDENTIFIER : [a-zA-Z_][a-zA-Z_0-9]* ;
NUMBER     : [0-9]+ ;
STRING     : '\'' ~[']* '\'' ;
WS         : [ \t\r\n]+ -> skip ;