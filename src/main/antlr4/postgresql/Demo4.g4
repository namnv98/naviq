grammar Demo4;
@header {
package com.naviq.antlr4.postgresql;
}

// 3 tầng lồng nhau, CẢ 3 đều preferred: ge (outermost) -> ide -> rid (innermost).
// Mô phỏng đúng general_element -> id_expression -> regular_id ngoài đời thật.
start : PREFIX ge ;
ge    : TOK1 ide ;
ide   : rid ;
rid   : ;

PREFIX : 'X' ;
TOK1   : 'Y' ;
WS     : [ \t\r\n]+ -> skip ;
