grammar Demo5;
@header {
package com.naviq.antlr4.postgresql;
}

// branchA và branchB đều bắt đầu bằng CÙNG 1 token (TOK1) -> ATN sống ở CẢ 2 nhánh cùng lúc
// (giống ví dụ "qualified_name: Identifier (DOT Identifier)?" trong tài liệu trinh sát).
// preferredA và preferredB KHÔNG lồng nhau, KHÔNG có quan hệ ancestor -> cả 2 phải cùng
// được ghi nhận, không cái nào được phép "nuốt" cái nào.
start   : PREFIX (branchA | branchB) ;
branchA : TOK1 preferredA ;
branchB : TOK1 preferredB ;
preferredA : ;
preferredB : ;

PREFIX : 'X' ;
TOK1   : 'Y' ;
WS     : [ \t\r\n]+ -> skip ;
