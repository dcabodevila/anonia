package com.docanonymizer.adapter.web;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Controles de escritorio para lanzadores sin consola. */
final class LauncherDialogs implements DesktopLauncher.Notifier {
    private LauncherDialogs() {}

    static DesktopLauncher.Notifier system() {
        return GraphicsEnvironment.isHeadless() ? DesktopLauncher.CONSOLE : new LauncherDialogs();
    }

    @Override public void error(String message) {
        Runnable show = () -> JOptionPane.showMessageDialog(null, message, "anonimuse", JOptionPane.ERROR_MESSAGE);
        // Esperamos al cierre del mensaje antes de devolver el estado de salida.
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(show);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException e) {
                // El mensaje ya se imprimio en consola; conservamos el estado de salida.
            }
        }
    }

    @Override public void running(URI uri, Runnable open, Runnable stop) {
        SwingUtilities.invokeLater(() -> {
            JDialog dialog = new JDialog();
            dialog.setTitle("anonimuse");
            dialog.setModal(false);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            JPanel content = new JPanel(new BorderLayout(0, 12));
            content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            content.add(new JLabel("anonimuse se está ejecutando en " + uri), BorderLayout.CENTER);
            JPanel buttons = new JPanel();
            JButton browse = new JButton("Abrir en el navegador");
            browse.addActionListener(event -> open.run());
            Runnable close = () -> {
                dialog.dispose();
                stop.run();
            };
            JButton halt = new JButton("Detener");
            halt.addActionListener(event -> close.run());
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { close.run(); }
            });
            buttons.add(browse);
            buttons.add(halt);
            content.add(buttons, BorderLayout.SOUTH);
            dialog.setContentPane(content);
            dialog.pack();
            dialog.setResizable(false);
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
        });
    }
}
