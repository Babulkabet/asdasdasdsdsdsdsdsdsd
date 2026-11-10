package dev.packetsender;

import java.util.Locale;

/**
 * Мини-калькулятор для переменных в JSON: числа, N, K, I, L, J, C, + - * / %, скобки, унарный минус.
 * N — номер пакета в запуске с 0, K — он же с 1, I — повтор пункта с 0,
 * L — круг очереди с 0, J — отправок текущего пункта с 0, C — отправок этого типа с 0.
 */
final class Expr {
    private final String s;
    private final double n, i, k, loop, item, type;
    private int p;

    private Expr(String s, Vars.Ctx c) {
        this.s = s;
        this.n = c.n();
        this.i = c.i();
        this.k = c.n() + 1;
        this.loop = c.loop();
        this.item = c.itemSent();
        this.type = c.typeSent();
    }

    /** Вычисляет выражение в контексте текущего пакета. */
    static Number eval(String expr, Vars.Ctx c) {
        Expr e = new Expr(expr.toLowerCase(Locale.ROOT), c);
        double v = e.expr();
        e.ws();
        if (e.p < e.s.length()) throw e.err("лишний символ «" + e.s.charAt(e.p) + "»");
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("переменная {" + s + "}: " + m);
    }

    private void ws() { while (p < s.length() && s.charAt(p) == ' ') p++; }

    private double expr() {
        double v = term();
        for (;;) {
            ws();
            if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
                char op = s.charAt(p++);
                double r = term();
                v = op == '+' ? v + r : v - r;
            } else return v;
        }
    }

    private double term() {
        double v = unary();
        for (;;) {
            ws();
            if (p < s.length() && "*/%".indexOf(s.charAt(p)) >= 0) {
                char op = s.charAt(p++);
                double r = unary();
                if ((op == '/' || op == '%') && r == 0) throw err("деление на ноль");
                v = op == '*' ? v * r : op == '/' ? v / r : v % r;
            } else return v;
        }
    }

    private double unary() {
        ws();
        if (p < s.length() && s.charAt(p) == '-') { p++; return -unary(); }
        return primary();
    }

    private double primary() {
        ws();
        if (p >= s.length()) throw err("неожиданный конец");
        char c = s.charAt(p);
        if (c == '(') {
            p++;
            double v = expr();
            ws();
            if (p >= s.length() || s.charAt(p) != ')') throw err("нет закрывающей скобки");
            p++;
            return v;
        }
        switch (c) {
            case 'n': p++; return n;
            case 'k': p++; return k;
            case 'i': p++; return i;
            case 'l': p++; return loop;
            case 'j': p++; return item;
            case 'c': p++; return type;
            default: break;
        }
        int st = p;
        while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
        if (st == p) throw err("неизвестный символ «" + c + "» (доступны N, K, I, L, J, C)");
        try {
            return Double.parseDouble(s.substring(st, p));
        } catch (NumberFormatException e) {
            throw err("некорректное число");
        }
    }
}
