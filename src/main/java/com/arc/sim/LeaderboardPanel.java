package com.arc.sim;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Live top-N table displayed for Engine 1/Engine 2 (most-favorable-conditions results) and
 * Engine 3 (closest-simulation-to-target results). A pure display component; ranking logic
 * resides in TopNLeaderboard, executed on the background job thread. This panel repaints on
 * receipt of each new snapshot.
 */
public class LeaderboardPanel extends JPanel {
    private final DefaultTableModel model;
    private final JTable table;

    public LeaderboardPanel(String title, String scoreColumnLabel) {
        super(new BorderLayout());
        String[] cols = {"#", scoreColumnLabel, "Apogee (m)", "Flight time (s)", "Conditions"};
        model = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(420);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder(title));
        scroll.setPreferredSize(new Dimension(880, 150));
        add(scroll, BorderLayout.CENTER);
    }

    /** Safe to invoke from any thread; internally dispatches to the EDT. */
    public void update(List<LeaderboardRow> rows) {
        SwingUtilities.invokeLater(() -> {
            model.setRowCount(0);
            for (LeaderboardRow r : rows) {
                model.addRow(new Object[]{
                        r.rank,
                        String.format("%.2f", r.score),
                        String.format("%.2f", r.apogeeM),
                        String.format("%.2f", r.flightTimeS),
                        r.detail
                });
            }
        });
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> model.setRowCount(0));
    }
}
