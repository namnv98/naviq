grammar Demo;
@header {
package com.naviq.antlr4.postgresql;
}

// "ide" (id_expression) VÀ "rid" (regular_id) đều preferred, "ide" LỒNG BAO NGOÀI "rid".
// "outer" gọi "ide" mà KHÔNG tốn token nào (RuleTransition thuần) -> ide được vào lúc CÒN LỜI
// (vì PREFIX đã tốn, còn TOK1 chưa tốn) -> KHÔNG bị shortcut chặn, đệ quy enterRule thật,
// đẩy "ide" vào stack thật. Bên trong ide, tốn nốt TOK1 rồi mới gọi "rid" -> đúng lúc TOK1 vừa
// tiêu xong, hết lời -> RuleTransition vào "rid" rơi đúng lúc atCaret=true, với stack ĐÃ CÓ "ide".
start : PREFIX outer ;
outer : ide ;
ide   : TOK1 rid ;
rid   : ;

PREFIX : 'X' ;
TOK1   : 'Y' ;
WS     : [ \t\r\n]+ -> skip ;