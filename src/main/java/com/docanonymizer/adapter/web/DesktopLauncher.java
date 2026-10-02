package com.docanonymizer.adapter.web;

import java.awt.Desktop;
import java.io.PrintStream;
import java.net.URI;
import java.net.BindException;
import com.sun.net.httpserver.HttpServer;
import java.util.concurrent.ExecutorService;

/** Entrada de escritorio independiente de CLI y Docker. */
public final class DesktopLauncher {
    private DesktopLauncher() {}

    @FunctionalInterface
    interface ServerStarter { HttpServer start(String host, int port) throws Exception; }
    @FunctionalInterface
    interface Browser { void open(URI uri) throws Exception; }

    interface Notifier {
        void error(String message);
        void running(URI uri, Runnable open, Runnable stop);
    }

    static final Notifier CONSOLE = new Notifier() {
        public void error(String message) {}
        public void running(URI uri, Runnable open, Runnable stop) {}
    };

    public static void main(String[] args) {
        int status = launch(args, (host, port) -> new WebServer(host, port).start(),
                uri -> Desktop.getDesktop().browse(uri), System.out, EdgeAppWindow.system(),
                LauncherDialogs.system(), System::exit);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int launch(String[] args, ServerStarter server, Browser browser, PrintStream out) {
        return launch(args, server, browser, out, null);
    }

    static int launch(String[] args, ServerStarter server, Browser browser, PrintStream out,
                      EdgeAppWindow window) {
        return launch(args, server, browser, out, window, CONSOLE, status -> {});
    }

    static int launch(String[] args, ServerStarter server, Browser browser, PrintStream out,
                      EdgeAppWindow window, Notifier notifier, java.util.function.IntConsumer exit) {
        int port;
        try {
            if (args.length > 1) {
                throw new IllegalArgumentException();
            }
            port = args.length == 0 ? 18080 : Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            return failure("Uso: DocAnonymizer [puerto entre 1 y 65535]. Predeterminado: 18080.", out, notifier);
        }
        URI uri = URI.create("http://127.0.0.1:" + port + "/");
        HttpServer running;
        try {
            try {
                running = server.start("127.0.0.1", port);
            } catch (BindException occupied) {
                if (args.length != 0) {
                    throw occupied;
                }
                running = server.start("127.0.0.1", 0);
            }
            uri = URI.create("http://127.0.0.1:" + running.getAddress().getPort() + "/");
        } catch (Exception e) {
            return failure("No se pudo iniciar " + uri
                    + ". Cierre otra instancia o pruebe otro puerto. Error: "
                    + e.getClass().getSimpleName(), out, notifier);
        }
        HttpServer started = running;
        Runnable stop = () -> stopServer(started);
        if (window != null && window.openAndWait(uri, stop, out)) {
            return 0;
        }
        out.println("Abra " + uri + " en su navegador. Para detener el servidor pulse Ctrl+C.");
        URI boundUri = uri;
        Runnable open = () -> openBrowser(browser, boundUri, out);
        open.run();
        notifier.running(boundUri, open, () -> {
            stop.run();
            exit.accept(0);
        });
        return 0;
    }

    private static int failure(String message, PrintStream out, Notifier notifier) {
        out.println(message);
        notifier.error(message);
        return 1;
    }

    private static void stopServer(HttpServer server) {
        server.stop(0);
        // HttpServer.stop no cierra el executor propio; sus hilos retendrian la JVM.
        if (server.getExecutor() instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    private static void openBrowser(Browser browser, URI uri, PrintStream out) {
        try {
            browser.open(uri);
        } catch (Exception e) {
            out.println("No se pudo abrir el navegador. Abra manualmente " + uri);
        }
    }
}
