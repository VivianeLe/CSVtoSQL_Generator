package com.example.demo;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class GeneratorController {
    @FXML
    private Label welcomeText;

    @FXML private TextField dbNameField, tableNameField, batchSizeField;
    @FXML private CheckBox checkTruncate, checkHeader;
    @FXML private RadioButton appendRadio, createRadio, upsertRadio;
    @FXML private TextField keyColumnField;
    @FXML private Label fileLabel, outputDirLabel;
    private File selectedCSV, selectedOutputDir;

    @FXML
    public void initialize() {
        appendRadio.setSelected(true); // optional default
    }

    @FXML
    public void handleUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choose CSV File");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        selectedCSV = fileChooser.showOpenDialog(null);
        if (selectedCSV != null) {
            fileLabel.setText(selectedCSV.getName());
        }
    }

    @FXML
    public void handleChooseOutputDir() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Choose Output Directory");
        selectedOutputDir = dirChooser.showDialog(null);
        if (selectedOutputDir != null) {
            outputDirLabel.setText(selectedOutputDir.getAbsolutePath());
        }
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

        String mode;
        if (appendRadio.isSelected()) {
            mode = "append";
        } else if (createRadio.isSelected()) {
            mode = "create";
        } else if (upsertRadio.isSelected()) {
            mode = "upsert";
        } else {
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
            List<String> lines = Files.readAllLines(selectedCSV.toPath());
            if (lines.isEmpty()) {
                showAlert("CSV is empty or has no data rows.");
                return;
            }

            int totalRows;
            String[] headers = lines.get(0).split(",", -1);
            for (int i = 0; i < headers.length; i++) {
                headers[i] = headers[i].trim().replaceAll(" ", "_");
            }

            if (header) {
                totalRows = lines.size() - 1;
            } else {
                totalRows = lines.size();
            }

            int numFiles = (int) Math.ceil((double) totalRows / batchSize);

            for (int i = 0; i < numFiles; i++) {
                int start = i * batchSize + 1;
                int end = Math.min(start + batchSize, lines.size());
                List<String> chunk = lines.subList(start, end);

                File outFile = new File(selectedOutputDir, tableName + (i + 1) + ".sql");
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(outFile))) {
                    writer.write("USE " + dbName + ";\n");

                    if ("create".equals(mode)) {
                        writer.write("DROP TABLE IF EXISTS " + tableName + ";\n");
                        writer.write("CREATE TABLE " + tableName + " (\n");
                        for (int j = 0; j < headers.length; j++) {
                            String colName = headers[j].trim();
                            writer.write("    " + colName + " NVARCHAR(50)");
                            if (j < headers.length - 1) writer.write(",\n");
                            else writer.write("\n");
                        }
                        writer.write(");\n");
                    }

                    if (truncate) {
                        writer.write("TRUNCATE TABLE " + tableName + ";\n");
                    }

                    if ("upsert".equals(mode)) {
                        String keyColumn = keyColumnField.getText().trim();
                        if (keyColumn.isEmpty()) {
                            showAlert("Please enter the key column for UPSERT.");
                            return;
                        }

                        // Validate key column exists
                        boolean keyExists = false;
                        for (String h : headers) {
                            if (h.equalsIgnoreCase(keyColumn)) {
                                keyExists = true;
                                break;
                            }
                        }
                        if (!keyExists) {
                            showAlert("Key column '" + keyColumn + "' not found in CSV header.");
                            return;
                        }

                        for (String line : chunk) {
                            String[] values = line.split(",", -1);
                            if (values.length != headers.length) continue;

                            StringBuilder insertCols = new StringBuilder();
                            StringBuilder insertVals = new StringBuilder();
                            StringBuilder updateSet = new StringBuilder();

                            for (int j = 0; j < headers.length; j++) {
                                String col = headers[j];
                                String val = values[j].replace("'", "''").trim();
                                String safeVal = val.isEmpty() ? "NULL" : "'" + val + "'";

                                insertCols.append(col).append(", ");
                                insertVals.append(safeVal).append(", ");

                                // Only update non-key columns
                                if (!col.equalsIgnoreCase(keyColumn)) {
                                    updateSet.append(col).append(" = ").append(safeVal).append(", ");
                                }
                            }

                            // Trim trailing commas
                            if (!insertCols.isEmpty()) insertCols.setLength(insertCols.length() - 2);
                            if (!insertVals.isEmpty()) insertVals.setLength(insertVals.length() - 2);
                            if (!updateSet.isEmpty()) updateSet.setLength(updateSet.length() - 2);

                            writer.write("INSERT INTO " + tableName + " (" + insertCols + ") VALUES (" + insertVals + ") ");
                            writer.write("ON DUPLICATE KEY UPDATE " + updateSet + ";\n\n");
                        }
                    }
                    else {

                        // Normal INSERT
                        writer.write("INSERT INTO " + tableName + " VALUES\n");
                        List<String> valuesList = new ArrayList<>();
                        for (String line : chunk) {
                            String[] values = line.split(",", -1);
                            StringBuilder sb = new StringBuilder("(");
                            for (int j = 0; j < values.length; j++) {
                                String val = values[j].replace("'", "''").trim();
                                if (val.isEmpty()) sb.append("NULL");
                                else sb.append("N'").append(val).append("'");
                                if (j < values.length - 1) sb.append(", ");
                            }
                            sb.append(")");
                            valuesList.add(sb.toString());
                        }
                        writer.write(String.join(",\n", valuesList) + ";\n");
                    }

//                    writer.write("INSERT INTO " + tableName + " VALUES\n");
//                    List<String> valuesList = new ArrayList<>();
//                    for (String line : chunk) {
//                        String[] values = line.split(",", -1);
//                        StringBuilder sb = new StringBuilder("(");
//                        for (int j = 0; j < values.length; j++) {
//                            String val = values[j].replace("'", "''").trim();
//                            if (val.isEmpty()) sb.append("NULL");
//                            else sb.append("N'").append(val).append("'");
//                            if (j < values.length - 1) sb.append(", ");
//                        }
//                        sb.append(")");
//                        valuesList.add(sb.toString());
//                    }
//                    writer.write(String.join(",\n", valuesList) + ";\n");
                }
            }

            showAlert("✅ SQL files generated in:\n" + selectedOutputDir.getAbsolutePath());

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