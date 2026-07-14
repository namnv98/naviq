package com.naviq;


import com.naviq.antlr4.oracle.PlSqlLexer;
import com.naviq.antlr4.oracle.PlSqlParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;

public class Log {

    public static void main(String[] args) {
        String sql = "SELECT * FROM users WHERE JSON_VALUE(json_col, '$.name' RETURNING VARCHAR2 ";

        CharStream input = CharStreams.fromString(sql);
        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokenStream);
        parser.removeErrorListeners();

        ParserRuleContext root = parser.unit_statement();

        printTree(root, parser);

//        System.out.println(org.antlr.v4.gui.Trees.inspect(root, parser));

    }


    public static void printTree(ParseTree tree, Parser parser) {
        printTree(tree, parser, "", true);
    }

    private static void printTree(ParseTree node, Parser parser, String prefix, boolean last) {
        String text;

        if (node instanceof RuleNode rule) {
            text = parser.getRuleNames()[rule.getRuleContext().getRuleIndex()];
        } else if (node instanceof ErrorNode) {
            text = "<ERROR> " + node.getText();
        } else if (node instanceof TerminalNode terminal) {
            text = parser.getVocabulary().getSymbolicName(terminal.getSymbol().getType())
                    + " : " + terminal.getText();
        } else {
            text = node.getText();
        }

        System.out.println(prefix + (last ? "└── " : "├── ") + text);

        for (int i = 0; i < node.getChildCount(); i++) {
            printTree(
                    node.getChild(i),
                    parser,
                    prefix + (last ? "    " : "│   "),
                    i == node.getChildCount() - 1
            );
        }
    }
}