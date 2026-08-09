package io.rigger.cli.output;

import java.util.List;

/**
 * Prints aligned ASCII tables to stdout — used by `get` commands.
 * Supports --output table (default), json, yaml, wide.
 */
public class TablePrinter {

    public static void print(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) widths[i] = headers.get(i).length();
        for (var row : rows)
            for (int i = 0; i < Math.min(row.size(), widths.length); i++)
                widths[i] = Math.max(widths[i], row.get(i) == null ? 0 : row.get(i).length());

        printRow(headers, widths);
        // separator
        var sep = new StringBuilder();
        for (int w : widths) sep.append("-".repeat(w + 2));
        System.out.println(sep);
        for (var row : rows) printRow(row, widths);
    }

    private static void printRow(List<String> cells, int[] widths) {
        var sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            String cell = i < cells.size() && cells.get(i) != null ? cells.get(i) : "";
            sb.append(String.format("%-" + widths[i] + "s  ", cell));
        }
        System.out.println(sb);
    }
}
