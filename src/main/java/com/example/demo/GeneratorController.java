package com.example.demo;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class GeneratorController {
    @FXML private TextField dbNameField, tableNameField, batchSizeField, keyColumnField;
    @FXML private CheckBox checkTruncate, checkHeader;
    @FXML private RadioButton appendRadio, createRadio, upsertRadio;
    @FXML private Label fileLabel, outputDirLabel;

    private File selectedCSV, selectedOutputDir;

    @FXML
    public void initialize() {
        appendRadio.setSelected(true);
    }

    @FXML
    public void handleUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choose CSV File");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        selectedCSV = fileChooser.showOpenDialog(null);
        if (selectedCSV != null) fileLabel.setText(selectedCSV.getName());
    }

    @FXML
    public void handleChooseOutputDir() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Choose Output Directory");
        selectedOutputDir = dirChooser.showDialog(null);
        if (selectedOutputDir != null) outputDirLabel.setText(selectedOutputDir.getAbsolutePath());
    }

    private List<String[]> readCSV(File file) throws IOException {
        List<String> rawLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        List<String> joinedLines = new ArrayList<>();
        StringBuilder record = new StringBuilder();
        boolean inQuotes = false;

        for (String line : rawLines) {
            long quoteCount = line.chars().filter(ch -> ch == '"').count();
            inQuotes ^= (quoteCount % 2 != 0); // flip if odd number of quotes
            record.append(line.replaceAll("[\n\t]+", " ")).append("\n");
            if (!inQuotes) {
                joinedLines.add(record.toString().trim());
                record.setLength(0);
            }
        }

        return joinedLines.stream()
                .map(this::parseCSVLine)
                .collect(Collectors.toList());
    }

    private String[] parseCSVLine(String line) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString().replaceAll("[\n\t]+", " ").trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().replaceAll("[\n\t]+", " ").trim());
        return tokens.toArray(new String[0]);
    }

    @FXML
    protected void onGenButtonClick() {
        if (selectedCSV == null || !selectedCSV.exists()) {
            showAlert("CSV file not selected.");
            return;
        }

        String dbName = dbNameField.getText().trim();
        String tableName = tableNameField.getText().trim();

        if (dbName.isEmpty() || tableName.isEmpty()) {
            showAlert("Database name and table name are required.");
            return;
        }

        String mode = appendRadio.isSelected() ? "append" : createRadio.isSelected() ? "create" : upsertRadio.isSelected() ? "upsert" : "";
        if (mode.isEmpty()) {
            showAlert("Please select a mode.");
            return;
        }

        boolean truncate = checkTruncate.isSelected();
        boolean header = checkHeader.isSelected();

        int batchSize;
        try {
            batchSize = Integer.parseInt(batchSizeField.getText().trim());
        } catch (NumberFormatException e) {
            showAlert("Invalid batch size.");
            return;
        }

        try {
            List<String[]> rows = readCSV(selectedCSV);
            if (rows.isEmpty()) {
                showAlert("CSV is empty or has no data rows.");
                return;
            }

            String[] rawHeaders = rows.get(0);
            List<String> allHeaders = new ArrayList<>();
            for (String col : rawHeaders) allHeaders.add(col.trim().replaceAll(" ", "_").replaceAll("-", "_"));

            List<String> selectedHeaders = new ArrayList<>();
            List<Integer> selectedIndices = new ArrayList<>();

            if ("user_registration".equalsIgnoreCase(tableName)) {
                List<String> targetColumns = List.of("userId", "channel", "registerTime");
                for (int i = 0; i < allHeaders.size(); i++) {
                    if (targetColumns.contains(allHeaders.get(i))) {
                        selectedIndices.add(i);
                        selectedHeaders.add(allHeaders.get(i));
                    }
                }
            } else {
                for (int i = 0; i < allHeaders.size(); i++) {
                    selectedIndices.add(i);
                    selectedHeaders.add(allHeaders.get(i));
                }
            }

            int startRow = header ? 1 : 0;
            int totalRows = rows.size() - startRow;
            int numFiles = (int) Math.ceil((double) totalRows / batchSize);

            for (int i = 0; i < numFiles; i++) {
                int start = startRow + i * batchSize;
                int end = Math.min(start + batchSize, rows.size());
                List<String[]> chunk = rows.subList(start, end);

                File outFile = new File(selectedOutputDir, tableName + (i + 1) + ".sql");
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(outFile))) {
                    writer.write("USE " + dbName + ";\n");

                    if ("create".equals(mode)) {
                        writer.write("DROP TABLE IF EXISTS " + tableName + ";\n");
                        writer.write("CREATE TABLE " + tableName + " (\n");
                        for (int j = 0; j < selectedHeaders.size(); j++) {
                            writer.write("    " + selectedHeaders.get(j) + " NVARCHAR(50)");
                            if (j < selectedHeaders.size() - 1) writer.write(",\n");
                            else writer.write("\n");
                        }
                        writer.write(");\n");
                    }

                    if (truncate) writer.write("TRUNCATE TABLE " + tableName + ";\n");

                    if ("upsert".equals(mode)) {
                        String keyColumn = keyColumnField.getText().trim();
                        if (keyColumn.isEmpty()) {
                            showAlert("Please enter the key column for UPSERT.");
                            return;
                        }
                        if (selectedHeaders.stream().noneMatch(h -> h.equalsIgnoreCase(keyColumn))) {
                            showAlert("Key column '" + keyColumn + "' not found in CSV header.");
                            return;
                        }

                        for (String[] values : chunk) {
                            StringBuilder insertCols = new StringBuilder();
                            StringBuilder insertVals = new StringBuilder();
                            StringBuilder updateSet = new StringBuilder();

                            for (int j = 0; j < selectedIndices.size(); j++) {
                                String col = selectedHeaders.get(j);
                                String val = selectedIndices.get(j) < values.length ? values[selectedIndices.get(j)].replace("'", "''").trim() : "";

                                if (col.equalsIgnoreCase("user_id") | col.equalsIgnoreCase("userid")) {
                                    val = val.replaceAll("\\D", "");
                                }

                                String safeVal = val.isEmpty() ? "NULL" : "'" + val + "'";
                                insertCols.append(col).append(", ");
                                insertVals.append(safeVal).append(", ");
                                if (!col.equalsIgnoreCase(keyColumn)) {
                                    updateSet.append(col).append(" = ").append(safeVal).append(", ");
                                }
                            }

                            if (!insertCols.isEmpty()) insertCols.setLength(insertCols.length() - 2);
                            if (!insertVals.isEmpty()) insertVals.setLength(insertVals.length() - 2);
                            if (!updateSet.isEmpty()) updateSet.setLength(updateSet.length() - 2);

                            writer.write("INSERT INTO " + tableName + " VALUES (" + insertVals + ") ");
                            writer.write("ON DUPLICATE KEY UPDATE " + updateSet + ";\n\n");
                        }
                    } else {
                        writer.write("INSERT INTO " + tableName + " VALUES\n");
                        List<String> valuesList = new ArrayList<>();
                        for (String[] values : chunk) {
                            StringBuilder sb = new StringBuilder("(");
                            for (int j = 0; j < selectedIndices.size(); j++) {
                                String col = selectedHeaders.get(j);
                                String val = selectedIndices.get(j) < values.length ? values[selectedIndices.get(j)].replace("'", "''").trim() : "";

                                if (col.equalsIgnoreCase("user_id") | col.equalsIgnoreCase("userid")) {
                                    val = val.replaceAll("\\D", "");
                                }

                                sb.append(val.isEmpty() ? "NULL" : "N'" + val + "'");
                                if (j < selectedIndices.size() - 1) sb.append(", ");
                            }
                            sb.append(")");
                            valuesList.add(sb.toString());
                        }
                        writer.write(String.join(",\n", valuesList) + ";\n");
                    }
                }
            }

            showAlert("\u2705 SQL files generated in:\n" + selectedOutputDir.getAbsolutePath());

        } catch (IOException ex) {
            ex.printStackTrace();
            showAlert("Error reading or writing files.");
        }
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.showAndWait();
    }
}
