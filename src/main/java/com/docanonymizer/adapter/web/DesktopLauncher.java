package com.docanonymizer.adapter.web;

import java.awt.Desktop;
import java.io.PrintStream;
import java.net.URI;

/** Entrada de escritorio independiente de CLI y Docker. */
public final class DesktopLauncher {
    private DesktopLauncher() {}

    @FunctionalInterface
    interface ServerStarter { void start(String host, int port) throws Exception; }
    @FunctionalInterface
    interface Browser { void open(URI uri) throws Exception; }

    public static void main(String[] args) {
        int status = launch(args, (host, port) -> new WebServer(host, port).start(),
                uri -> Desktop.getDesktop().browse(uri), System.out);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int launch(String[] args, ServerStarter server, Browser browser, PrintStream out) {
        int port;
        try {
            if (args.length > 1) {
                throw new IllegalArgumentException();
            }
            port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            out.println("Uso: DocAnonymizer [puerto entre 1 y 65535]. Predeterminado: 8080.");
            return 1;
        }
        URI uri = URI.create("http://127.0.0.1:" + port + "/");
        try {
            server.start("127.0.0.1", port);
        } catch (Exception e) {
            out.println("No se pudo iniciar " + uri
                    + ". Cierre otra instancia o pruebe otro puerto. Error: "
                    + e.getClass().getSimpleName());
            return 1;
        }
        out.println("Abra " + uri + " en su navegador. Para detener el servidor pulse Ctrl+C.");
        try {
            browser.open(uri);
        } catch (Exception e) {
            out.println("No se pudo abrir el navegador. Abra manualmente " + uri);
        }
        return 0;
    }
}
