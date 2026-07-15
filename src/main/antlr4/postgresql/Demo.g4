grammar Demo;
@header {
package com.naviq.antlr4.postgresql;
}

// Khớp đúng grammar ví dụ trong ATN_ROOM_DOOR_ANALOGY.md, mục "Ví dụ mở rộng":
//   select_stmt : SELECT columnref (COMMA columnref)* FROM qualified_name (WHERE bool_expr)? ;
// Thêm EOF ở cuối để engine biết chắc khi nào câu thật sự kết thúc.
select_stmt
    : SELECT columnref (COMMA columnref)* FROM qualified_name (WHERE bool_expr)? EOF
    ;

// Mê cung "vô nghĩa" (không nên là VIP) — chỉ là 1 Identifier trần trụi.
columnref
    : IDENTIFIER
    ;

// Mê cung "có ý nghĩa" (ứng viên tốt để đánh dấu preferred/VIP) — test đúng
// hiện tượng "1 từ khớp nhiều cửa cùng lúc" (mục cuối tài liệu): gõ 1
// Identifier, engine đang sống song song ở CẢ 2 nhánh — "không có schema"
// (RULE_STOP ngay) và "có schema, đang chờ DOT" — cho tới khi rõ DOT có hay không.
qualified_name
    : IDENTIFIER (DOT IDENTIFIER)?
    ;

// Đơn giản hoá bool_expr thành 1 nhánh duy nhất (không rẽ nhánh
// quantitative/bình thường như grammar Oracle thật) — đủ để test where_clause
// có mở ra ngã rẽ sinh gợi ý đúng lúc caret rơi vào hay không.
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