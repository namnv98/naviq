grammar PostgreSQL;

@header {
    package com.example;
}

// ===================== PARSER =====================

query
    : withClause? statement SEMI? EOF
    ;

statement
    : selectStmt
    | insertStmt
    | updateStmt
    | deleteStmt
    | createTableStmt
    | dropTableStmt
    | truncateStmt
    | alterTableStmt
    | createFunctionStmt
    | dropFunctionStmt
    ;

// ===================== CTE =====================

withClause
    : WITH cteDefinition (COMMA cteDefinition)*
    ;

cteDefinition
    : cteName AS LPAREN selectStmt RPAREN
    ;

cteName
    : identifier
    ;

// ===================== SELECT =====================

selectStmt
    : SELECT selectElements FROM tableSources
      whereClause?
      groupByClause?
      havingClause?
      orderByClause?
      limitClause?
    ;

selectElements
    : STAR
    | selectElement (COMMA selectElement)*
    ;

selectElement
    : qualifiedName DOT STAR
    | expression (AS? selectAlias)?
    ;

selectAlias
    : identifier
    ;

// ===================== TABLE SOURCE =====================

tableSources
    : tableSource joinClause*
    ;

tableSource
    : tableName (AS? tableAlias)?                       #simpleTable
    | LPAREN selectStmt RPAREN (AS? tableAlias)?        #subqueryTable
    ;

tableAlias
    : identifier
    ;

tableName
    : qualifiedName
    ;

// ===================== JOIN =====================

joinClause
    : joinType? JOIN tableSource joinCondition?
    ;

joinType
    : INNER
    | LEFT OUTER?
    | RIGHT OUTER?
    | FULL OUTER?
    | CROSS
    | NATURAL
    ;

joinCondition
    : ON expression
    | USING LPAREN columnList RPAREN
    ;

// ===================== CLAUSES =====================

whereClause
    : WHERE expression
    ;

groupByClause
    : GROUP BY expression (COMMA expression)*
    ;

havingClause
    : HAVING expression
    ;

orderByClause
    : ORDER BY orderItem (COMMA orderItem)*
    ;

orderItem
    : expression (ASC | DESC)?
    ;

limitClause
    : LIMIT NUMBER (OFFSET NUMBER)?
    ;

// ===================== EXPRESSION =====================
// functionCall đứng TRƯỚC columnName ở mọi alternative
// ANTLR dùng lookahead tự nhiên: thấy ID LPAREN → functionCall
//                                 thấy ID (không có LPAREN) → columnName

expression
    : primary                                                               #exprPrimary
    | expression op=(STAR | SLASH) expression                               #exprMulDiv
    | expression op=(PLUS | MINUS) expression                               #exprAddSub
    | expression op=(EQ | GT | LT | GTE | LTE | NEQ) expression            #exprCompare
    | expression AND expression                                             #exprAnd
    | expression OR expression                                              #exprOr
    | NOT expression                                                        #exprNot
    | expression IS NULL_                                                   #exprIsNull
    | expression IS NOT NULL_                                               #exprIsNotNull
    | expression IN LPAREN expressionList RPAREN                            #exprIn
    | expression NOT IN LPAREN expressionList RPAREN                        #exprNotIn
    | expression LIKE expression                                            #exprLike
    | EXISTS LPAREN selectStmt RPAREN                                       #exprExists
    | LPAREN selectStmt RPAREN                                              #exprSubquery
    ;

expressionList
    : expression (COMMA expression)*
    ;

// ===================== PRIMARY =====================
// Tách rõ: functionCall (ID LPAREN) vs columnName (ID)
// ANTLR k4 dùng ALL(*) parsing nên tự resolve bằng lookahead
// primary → functionCall phải đứng TRƯỚC columnName

primary
    : literal                                               #primaryLiteral
    | columnName                                            #primaryColumn
    | functionCall                                          #primaryFunction
    | LPAREN expression RPAREN                              #primaryParen
    | CASE caseWhen+ (ELSE expression)? END                 #primaryCase
    ;

caseWhen
    : WHEN expression THEN expression
    ;

// functionCall bắt buộc có LPAREN sau tên → ATN phân biệt được với columnName
functionCall
    : functionName LPAREN (STAR | expressionList)? RPAREN
    ;

// Rule riêng để preferredRules bắt được
functionName
    : qualifiedName
    ;

// ===================== COLUMN LIST =====================

columnList
    : columnName (COMMA columnName)*
    ;

// ===================== INSERT =====================

insertStmt
    : INSERT INTO tableName LPAREN columnList RPAREN
      VALUES LPAREN valueList RPAREN
    ;

valueList
    : literal (COMMA literal)*
    ;

// ===================== UPDATE =====================

updateStmt
    : UPDATE tableName (AS? tableAlias)?
      SET setClause (COMMA setClause)*
      whereClause?
    ;

setClause
    : columnName EQ expression
    ;

// ===================== DELETE =====================

deleteStmt
    : DELETE FROM tableName (AS? tableAlias)?
      whereClause?
    ;

// ===================== CREATE TABLE =====================

createTableStmt
    : CREATE TABLE (IF NOT EXISTS)? tableName
      LPAREN columnDefList RPAREN
    ;

columnDefList
    : columnDef (COMMA columnDef)*
    ;

columnDef
    : columnName dataType columnConstraint*
    ;

dataType
    : dataTypeName (LPAREN NUMBER (COMMA NUMBER)? RPAREN)?
    ;

dataTypeName
    : identifier
    ;

columnConstraint
    : NOT NULL_                             #constraintNotNull
    | NULL_                                 #constraintNull
    | PRIMARY KEY                           #constraintPrimaryKey
    | UNIQUE                                #constraintUnique
    | DEFAULT literal                       #constraintDefault
    | CHECK LPAREN expression RPAREN        #constraintCheck
    ;

// ===================== DROP TABLE =====================

dropTableStmt
    : DROP TABLE (IF EXISTS)? tableName (COMMA tableName)*
    ;

// ===================== TRUNCATE =====================

truncateStmt
    : TRUNCATE TABLE? tableName
    ;

// ===================== ALTER TABLE =====================

alterTableStmt
    : ALTER TABLE tableName alterAction (COMMA alterAction)*
    ;

alterAction
    : ADD COLUMN? columnDef                                         #alterAddColumn
    | DROP COLUMN? (IF EXISTS)? columnName                          #alterDropColumn
    | ALTER COLUMN? columnName SET dataType                         #alterSetType
    | ALTER COLUMN? columnName SET DEFAULT literal                  #alterSetDefault
    | ALTER COLUMN? columnName DROP DEFAULT                         #alterDropDefault
    | RENAME COLUMN columnName TO columnName                        #alterRenameColumn
    | RENAME TO tableName                                           #alterRenameTable
    | ADD tableConstraint                                           #alterAddConstraint
    | DROP CONSTRAINT (IF EXISTS)? identifier                       #alterDropConstraint
    ;

tableConstraint
    : (CONSTRAINT identifier)?
      ( PRIMARY KEY LPAREN columnList RPAREN
      | UNIQUE       LPAREN columnList RPAREN
      | CHECK        LPAREN expression RPAREN
      )
    ;

// ===================== CREATE FUNCTION =====================

createFunctionStmt
    : CREATE (OR REPLACE)? FUNCTION qualifiedName
      LPAREN functionParamList? RPAREN
      RETURNS returnType
      functionOption*
      AS dollarBody
      functionOption*
    ;

functionParamList
    : functionParam (COMMA functionParam)*
    ;

functionParam
    : paramMode? identifier? dataType
    ;

paramMode
    : IN
    | OUT
    | INOUT
    | VARIADIC
    ;

returnType
    : TABLE LPAREN columnDefList RPAREN
    | SETOF dataType
    | dataType
    ;

functionOption
    : LANGUAGE identifier
    | SECURITY DEFINER
    | SECURITY INVOKER
    | STRICT
    | IMMUTABLE
    | STABLE
    | VOLATILE
    | COST NUMBER
    | ROWS NUMBER
    ;

dollarBody
    : DOLLAR_BODY
    ;

// ===================== DROP FUNCTION =====================

dropFunctionStmt
    : DROP FUNCTION (IF EXISTS)?
      qualifiedName LPAREN functionParamList? RPAREN
      (CASCADE | RESTRICT)?
    ;

// ===================== QUALIFIED NAME =====================

qualifiedName
    : identifier (DOT identifier)*
    ;

columnName
    : qualifiedName
    ;

// ===================== BASIC =====================

identifier
    : ID
    ;

literal
    : STRING
    | NUMBER
    ;

// ===================== LEXER =====================

fragment A : [Aa]; fragment B : [Bb]; fragment C : [Cc];
fragment D : [Dd]; fragment E : [Ee]; fragment F : [Ff];
fragment G : [Gg]; fragment H : [Hh]; fragment I : [Ii];
fragment J : [Jj]; fragment K : [Kk]; fragment L : [Ll];
fragment M : [Mm]; fragment N : [Nn]; fragment O : [Oo];
fragment P : [Pp]; fragment Q : [Qq]; fragment R : [Rr];
fragment S : [Ss]; fragment T : [Tt]; fragment U : [Uu];
fragment V : [Vv]; fragment W : [Ww]; fragment X : [Xx];
fragment Y : [Yy]; fragment Z : [Zz];

// keywords — DML
SELECT      : S E L E C T;
INSERT      : I N S E R T;
INTO        : I N T O;
VALUES      : V A L U E S;
UPDATE      : U P D A T E;
SET         : S E T;
DELETE      : D E L E T E;

// keywords — DDL
CREATE      : C R E A T E;
TABLE       : T A B L E;
DROP        : D R O P;
TRUNCATE    : T R U N C A T E;
ALTER       : A L T E R;
ADD         : A D D;
COLUMN      : C O L U M N;
RENAME      : R E N A M E;
TO          : T O;
IF          : I F;
CONSTRAINT  : C O N S T R A I N T;
PRIMARY     : P R I M A R Y;
KEY         : K E Y;
UNIQUE      : U N I Q U E;
DEFAULT     : D E F A U L T;
CHECK       : C H E C K;

// keywords — function
FUNCTION    : F U N C T I O N;
RETURNS     : R E T U R N S;
RETURN      : R E T U R N;
LANGUAGE    : L A N G U A G E;
SECURITY    : S E C U R I T Y;
DEFINER     : D E F I N E R;
INVOKER     : I N V O K E R;
STRICT      : S T R I C T;
IMMUTABLE   : I M M U T A B L E;
STABLE      : S T A B L E;
VOLATILE    : V O L A T I L E;
COST        : C O S T;
ROWS        : R O W S;
SETOF       : S E T O F;
IN          : I N;
OUT         : O U T;
INOUT       : I N O U T;
VARIADIC    : V A R I A D I C;
CASCADE     : C A S C A D E;
RESTRICT    : R E S T R I C T;
OR          : O R;
REPLACE     : R E P L A C E;

// keywords — query
FROM        : F R O M;
WHERE       : W H E R E;
GROUP       : G R O U P;
BY          : B Y;
ORDER       : O R D E R;
HAVING      : H A V I N G;
LIMIT       : L I M I T;
OFFSET      : O F F S E T;
ASC         : A S C;
DESC        : D E S C;
AS          : A S;
WITH        : W I T H;
JOIN        : J O I N;
INNER       : I N N E R;
LEFT        : L E F T;
RIGHT       : R I G H T;
FULL        : F U L L;
OUTER       : O U T E R;
CROSS       : C R O S S;
NATURAL     : N A T U R A L;
ON          : O N;
USING       : U S I N G;
AND         : A N D;
NOT         : N O T;
LIKE        : L I K E;
EXISTS      : E X I S T S;
IS          : I S;
NULL_       : N U L L;
CASE        : C A S E;
WHEN        : W H E N;
THEN        : T H E N;
ELSE        : E L S E;
END         : E N D;

// punctuation
SEMI        : ';';
STAR        : '*';
COMMA       : ',';
DOT         : '.';
LPAREN      : '(';
RPAREN      : ')';

// operators
EQ          : '=';
NEQ         : '!=';
LT          : '<';
GT          : '>';
LTE         : '<=';
GTE         : '>=';
PLUS        : '+';
MINUS       : '-';
SLASH       : '/';

// dollar-quoted body
DOLLAR_BODY
    : '$$' .*? '$$'
    | '$' [a-zA-Z_][a-zA-Z_0-9]* '$' .*? '$' [a-zA-Z_][a-zA-Z_0-9]* '$'
    ;

ID
    : [a-zA-Z_][a-zA-Z_0-9]*
    ;

NUMBER
    : [0-9]+ ('.' [0-9]+)?
    ;

STRING
    : '\'' (~['\\] | '\\' .)* '\''
    ;

WS
    : [ \t\r\n]+ -> skip
    ;
