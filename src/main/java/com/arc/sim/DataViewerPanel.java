package com.arc.sim;

import org.apache.poi.ss.usermodel.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.Color;
import java.io.File;
import java.util.List;
import java.util.Vector;

public class DataViewerPanel extends JPanel {

    private static final int DEFAULT_ROW_CAP = 20_000;

    private final JTextField pathField = new JTextField();
    private final JComboBox<String> sheetCombo = new JComboBox<>();
    private final JLabel infoLabel = new JLabel(" ");
    private final JSpinner rowCapSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_ROW_CAP, 100, 5_000_000, 1000));
    private final JTable table = new JTable();

    private Workbook openWorkbook;
    private File openFile;

    public DataViewerPanel() {
        super(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        top.add(new JLabel("File (.xlsx / .csv / .parquet):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        top.add(pathField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        JButton browseButton = new JButton("Browse...");
        top.add(browseButton, gbc);
        gbc.gridx = 3;
        JButton openButton = new JButton("Open");
        top.add(openButton, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        top.add(new JLabel("Sheet (xlsx only):"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 1;
        sheetCombo.setEnabled(false);
        top.add(sheetCombo, gbc);
        gbc.gridx = 2;
        top.add(new JLabel("Row cap:"), gbc);
        gbc.gridx = 3;
        top.add(rowCapSpinner, gbc);

        browseButton.addActionListener(e -> browse());
        openButton.addActionListener(e -> openCurrentPath());
        sheetCombo.addActionListener(e -> {
            if (sheetCombo.isEnabled() && openWorkbook != null && sheetCombo.getSelectedItem() != null) {
                loadXlsxSheet((String) sheetCombo.getSelectedItem());
            }
        });

        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);

        JPanel center = new JPanel(new BorderLayout());
        center.add(top, BorderLayout.NORTH);
        center.add(new JScrollPane(table), BorderLayout.CENTER);
        infoLabel.setForeground(Color.GRAY);

        add(center, BorderLayout.CENTER);
        add(infoLabel, BorderLayout.SOUTH);

        setEmptyModel("Pick a .xlsx, .csv, or .parquet file above and click Open.");
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser(pathField.getText().trim().isEmpty() ? System.getProperty("user.dir") : pathField.getText().trim());
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Data files (*.xlsx, *.csv, *.parquet)", "xlsx", "csv", "parquet"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
            openCurrentPath();
        }
    }

    private void openCurrentPath() {
        String path = pathField.getText().trim();
        if (path.isEmpty()) {
            infoLabel.setText("Choose a file first.");
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            infoLabel.setText("File not found: " + path);
            return;
        }
        String lower = f.getName().toLowerCase();
        try {
            closeWorkbookIfOpen();
            if (lower.endsWith(".xlsx")) {
                openXlsx(f);
            } else if (lower.endsWith(".csv")) {
                sheetCombo.setEnabled(false);
                sheetCombo.removeAllItems();
                openCsv(f);
            } else if (lower.endsWith(".parquet")) {
                sheetCombo.setEnabled(false);
                sheetCombo.removeAllItems();
                openParquet(f);
            } else {
                infoLabel.setText("Unsupported file type -- expected .xlsx, .csv, or .parquet.");
            }
        } catch (Exception ex) {
            setEmptyModel("Could not open file: " + ex.getMessage());
            infoLabel.setText("Error opening " + f.getName());
        }
    }

    private void closeWorkbookIfOpen() {
        if (openWorkbook != null) {
            try { openWorkbook.close(); } catch (Exception ignored) { }
            openWorkbook = null;
        }
    }

    private void openXlsx(File f) throws Exception {
        openWorkbook = WorkbookFactory.create(f, null, true);
        openFile = f;
        sheetCombo.removeAllItems();
        for (int i = 0; i < openWorkbook.getNumberOfSheets(); i++) {
            sheetCombo.addItem(openWorkbook.getSheetName(i));
        }
        sheetCombo.setEnabled(sheetCombo.getItemCount() > 0);
        if (sheetCombo.getItemCount() > 0) {
            sheetCombo.setSelectedIndex(0);
            loadXlsxSheet(openWorkbook.getSheetName(0));
        }
    }

    private void loadXlsxSheet(String sheetName) {
        try {
            Sheet sheet = openWorkbook.getSheet(sheetName);
            if (sheet == null) return;
            int rowCap = (Integer) rowCapSpinner.getValue();
            DataFormatter formatter = new DataFormatter();

            int firstRow = sheet.getFirstRowNum();
            int lastRow = sheet.getLastRowNum();
            Row headerRow = sheet.getRow(firstRow);
            int numCols = headerRow == null ? 0 : headerRow.getLastCellNum();
            Vector<String> columns = new Vector<>();
            if (headerRow != null) {
                for (int c = 0; c < numCols; c++) {
                    Cell cell = headerRow.getCell(c);
                    String v = cell == null ? "" : formatter.formatCellValue(cell);
                    columns.add(v.isEmpty() ? ("col" + c) : v);
                }
            }

            Vector<Vector<Object>> data = new Vector<>();
            int shown = 0;
            for (int r = firstRow + 1; r <= lastRow && shown < rowCap; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Vector<Object> rowData = new Vector<>();
                for (int c = 0; c < numCols; c++) {
                    Cell cell = row.getCell(c);
                    rowData.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                data.add(rowData);
                shown++;
            }

            table.setModel(new DefaultTableModel(data, columns));
            int totalDataRows = Math.max(0, lastRow - firstRow);
            infoLabel.setText(String.format("Sheet '%s': showing %,d of %,d row(s)%s", sheetName, shown, totalDataRows,
                    shown < totalDataRows ? " (raise the row cap to see more)" : ""));
        } catch (Exception ex) {
            setEmptyModel("Could not read sheet: " + ex.getMessage());
        }
    }

    private void openCsv(File f) throws Exception {
        CsvUtil.Table t = CsvUtil.read(f);
        int rowCap = (Integer) rowCapSpinner.getValue();
        Vector<String> columns = new Vector<>(t.header);
        Vector<Vector<Object>> data = new Vector<>();
        int shown = 0;
        for (List<String> row : t.rows) {
            if (shown >= rowCap) break;
            Vector<Object> rowData = new Vector<>();
            for (int c = 0; c < columns.size(); c++) {
                rowData.add(c < row.size() ? row.get(c) : "");
            }
            data.add(rowData);
            shown++;
        }
        table.setModel(new DefaultTableModel(data, columns));
        infoLabel.setText(String.format("CSV: showing %,d of %,d row(s)%s", shown, t.rows.size(),
                shown < t.rows.size() ? " (raise the row cap to see more)" : ""));
    }

    private void openParquet(File f) throws Exception {
        int rowCap = (Integer) rowCapSpinner.getValue();
        MiniParquet.ReadResult result = MiniParquet.read(f, rowCap);
        long totalRows;
        try {
            totalRows = MiniParquet.countRows(f);
        } catch (Exception ex) {
            totalRows = result.rows.size();
        }
        Vector<String> columns = new Vector<>(result.columnNames);
        Vector<Vector<Object>> data = new Vector<>();
        for (Object[] row : result.rows) {
            Vector<Object> rowData = new Vector<>();
            for (Object v : row) rowData.add(v == null ? "" : v);
            data.add(rowData);
        }
        table.setModel(new DefaultTableModel(data, columns));
        infoLabel.setText(String.format("Parquet: showing %,d of %,d row(s)%s -- columns: %s",
                result.rows.size(), totalRows, result.rows.size() < totalRows ? " (raise the row cap to see more)" : "",
                String.join(", ", result.columnNames)));
    }

    private void setEmptyModel(String message) {
        table.setModel(new DefaultTableModel(new Vector<>(), new Vector<>()));
        infoLabel.setText(message);
    }
}

