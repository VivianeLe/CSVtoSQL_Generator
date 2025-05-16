package com.example.demo;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GeneratorController {
    @FXML private TextField dbNameField, tableNameField, batchSizeField;
    @FXML private CheckBox checkTruncate, checkHeader, checkLastModify;
    @FXML private RadioButton appendRadio, createRadio;
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
                if (c == '\n' || c == '\t') {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
        }
        tokens.add(sb.toString().replaceAll("[\n\t]+", " ").trim());
        return tokens.toArray(new String[0]);
    }

    // move declaration inside methods to avoid premature evaluation
    private boolean isAddLastModifySelected() {
        return checkLastModify != null && checkLastModify.isSelected();
    }

    @FXML
    protected void onCleanButtonClick() {
        String tableName = tableNameField.getText().trim();

        if (selectedCSV == null || !selectedCSV.exists()) {
            showAlert("CSV file not selected.");
            return;
        }

        if (tableName == null || tableName.isEmpty()) {
            showAlert("Table name is not inputted");
            return;
        }

        boolean add_lastModify = isAddLastModifySelected();
        boolean hasHeader = checkHeader.isSelected();
        File outFile = new File(selectedOutputDir, tableName + "_cleaned.csv");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(selectedCSV), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new FileWriter(outFile))) {

            String headerLine = hasHeader ? reader.readLine() : null;
            if (hasHeader && headerLine == null) {
                showAlert("CSV file is empty.");
                return;
            }

            List<String> allHeaders = new ArrayList<>();
            if (hasHeader && headerLine != null) {
                String[] rawHeaders = parseCSVLine(headerLine);
                for (String col : rawHeaders)
                    allHeaders.add(col.trim().replaceAll(" ", "_").replaceAll("-", "_"));
            }

            // === user_birthday table ===
            if ("user_age".equalsIgnoreCase(tableName)) {
                writer.write("User_ID,age" + (add_lastModify ? ",last_modify_time" : "") + "\n");

                reader.lines().forEach(line -> {
                    String[] parsed = parseCSVLine(line);
                    if (parsed.length >= 6) {
                        String userId = parsed[0].replaceAll("\\D", "").trim();
                        String birthdayRaw = parsed[5].trim();
                        String registerTime = parsed[2].trim().split(" ")[0];

                        try {
                            java.time.LocalDate registerDate = java.time.LocalDate.parse(registerTime);
                            java.time.LocalDate cutoffDate = java.time.LocalDate.of(2024, 11, 23);

                            if (!birthdayRaw.isEmpty() && !birthdayRaw.equalsIgnoreCase("null") &&
                                    !registerDate.isBefore(cutoffDate)) {

                                String birthdayFormatted = birthdayRaw.split(" ")[0];
                                java.time.LocalDate birthDate = java.time.LocalDate.parse(birthdayFormatted);

                                int age = java.time.Period.between(birthDate, java.time.LocalDate.now()).getYears();
                                String row = userId + "," + age + (add_lastModify ? "," + java.time.LocalDateTime.now() : "");
                                writer.write(row + "\n");
                            }
                        } catch (Exception e) {
                            // Skip rows with invalid date formats
                        }
                    }
                });

                showAlert("\u2705 Cleaned user_birthday file saved to:\n" + outFile.getAbsolutePath());
                return;
            }

            // === RGlimit table ===
            if ("rglimit".equalsIgnoreCase(tableName)) {
                writer.write("User_ID,RG_limit" + (add_lastModify ? ",last_modify_time" : "") + "\n");
                Set<String> seenUserIds = new HashSet<>();

                reader.lines().forEach(line -> {
                    String[] parsed = parseCSVLine(line);
                    if (parsed.length >= 1) {
                        String userId = parsed[0].replaceAll("\\D", "").trim();
                        if (!userId.isEmpty() && seenUserIds.add(userId)) {
                            String row = userId + ",1" + (add_lastModify ? "," + java.time.LocalDateTime.now() : "");
                            try {
                                writer.write(row + "\n");
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });

                showAlert("\u2705 RGlimit cleaned file saved to:\n" + outFile.getAbsolutePath());
                return;
            }

            // === Default table (e.g., order) ===
            List<String> selectedHeaders = new ArrayList<>();
            List<Integer> selectedIndices = new ArrayList<>();

            if (("order".equalsIgnoreCase(tableName) || "order_management".equalsIgnoreCase(tableName)) && hasHeader) {
                List<String> targetColumns = List.of("ID", "User_ID", "Lottery", "Series_No.",
                        "Entries", "Amount", "Creation_date", "Winning_status", "Pre_tax_bonus");
                for (int i = 0; i < allHeaders.size(); i++) {
                    if (targetColumns.contains(allHeaders.get(i))) {
                        selectedIndices.add(i);
                        selectedHeaders.add(allHeaders.get(i));
                    }
                }
            }

            selectedHeaders.add("DateID");
            if (add_lastModify) {
                selectedHeaders.add("last_modify_time");
            }
            writer.write(String.join(",", selectedHeaders));
            writer.newLine();

            StringBuilder record = new StringBuilder();
            boolean inQuotes = false;
            String line;

            while ((line = reader.readLine()) != null) {
                record.append(line).append("\n");
                long quoteCount = line.chars().filter(ch -> ch == '"').count();
                if (quoteCount % 2 != 0) inQuotes = !inQuotes;

                if (!inQuotes) {
                    String[] parsed = parseCSVLine(record.toString());
                    record.setLength(0);

                    List<String> cleaned = new ArrayList<>();
                    String creationDate = "";

                    for (int j = 0; j < selectedIndices.size(); j++) {
                        String col = selectedHeaders.get(j);
                        String val = selectedIndices.get(j) < parsed.length ? parsed[selectedIndices.get(j)].trim() : "";

                        if (col.equalsIgnoreCase("user_id") || col.equalsIgnoreCase("id")) {
                            val = val.replaceAll("\\D", "");
                        }

                        if (col.equalsIgnoreCase("Creation_date")) {
                            creationDate = val.split(" ")[0];
                        }

                        cleaned.add(val);
                    }

                    cleaned.add(creationDate);
                    if (add_lastModify) {
                        cleaned.add(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    }
                    writer.write(String.join(",", cleaned));
                    writer.newLine();
                }
            }

            showAlert("\u2705 CSV cleaned file generated in:\n" + outFile.getAbsolutePath());

        } catch (IOException ex) {
            ex.printStackTrace();
            showAlert("Error processing the CSV file.");
        }
    }



    @FXML
    protected void onGenButtonClick() {
        boolean add_lastModify = isAddLastModifySelected();

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

        String mode = appendRadio.isSelected() ? "append" : createRadio.isSelected() ? "create" : "";
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

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(selectedCSV), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                showAlert("CSV file is empty.");
                return;
            }

            String[] rawHeaders = parseCSVLine(headerLine);
            List<String> allHeaders = new ArrayList<>();
            for (String col : rawHeaders) allHeaders.add(col.trim().replaceAll(" ", "_").replaceAll("-", "_"));

            List<String> selectedHeaders = new ArrayList<>();
            List<Integer> selectedIndices = new ArrayList<>();

            if ("user_registration".equalsIgnoreCase(tableName) |
                    "dim_user_registration".equalsIgnoreCase(tableName)) {
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

            int fileIndex = 1;
            List<String[]> batch = new ArrayList<>();
            StringBuilder record = new StringBuilder();
            boolean inQuotes = false;
            String line;

            while ((line = reader.readLine()) != null) {
                record.append(line).append("\n");
                long quoteCount = line.chars().filter(ch -> ch == '"').count();
                if (quoteCount % 2 != 0) inQuotes = !inQuotes;
                if (!inQuotes) {
                    String[] parsed = parseCSVLine(record.toString());
                    batch.add(parsed);
                    record.setLength(0);
                }

                if (batch.size() >= batchSize) {
                    writeSQLFile(batch, fileIndex++, dbName, tableName, selectedHeaders, selectedIndices, mode, truncate, add_lastModify);
                    batch.clear();
                    truncate = false;
                }
            }
            if (!batch.isEmpty()) {
                writeSQLFile(batch, fileIndex, dbName, tableName, selectedHeaders, selectedIndices, mode, truncate, add_lastModify);
            }

            showAlert("\u2705 SQL files generated in:\n" + selectedOutputDir.getAbsolutePath());

        } catch (IOException e) {
            e.printStackTrace();
            showAlert("Error processing the CSV file.");
        }
    }

    private static final Set<String> NUMERIC_COLUMNS = Set.of(
            "user_id", "user id", "uid"
    );

    private static final Set<String> MONEY_COLUMNS = Set.of(
            "top_up_amount", "personal_expense", "company_expenses", "has_arrived",
            "withdrawal_amount_(aed)"
    );

    private static final Set<String> DATETIME_COLUMNS = Set.of(
            "top_up_completion_time"
    );

    private void writeSQLFile(List<String[]> chunk, int fileIndex, String dbName, String tableName,
                              List<String> headers, List<Integer> indices, String mode, boolean truncate, boolean add_lastModify) throws IOException {

        File outFile = new File(selectedOutputDir, tableName + fileIndex + ".sql");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outFile))) {
            writer.write("USE " + dbName + ";\n");

            if ("create".equals(mode)) {
                writer.write("DROP TABLE IF EXISTS " + tableName + ";\n");
                writer.write("CREATE TABLE " + tableName + " (\n");
                for (int j = 0; j < headers.size(); j++) {
                    writer.write("    " + headers.get(j) + " NVARCHAR(50)");
                    if (j < headers.size() - 1) writer.write(",\n");
                    else writer.write("\n");
                }
                if (add_lastModify) {
                    writer.write(",\n    last_modify_time NVARCHAR(50)\n");
                }
                writer.write(");\n");
            }

            if (truncate) writer.write("TRUNCATE TABLE " + tableName + ";\n");

            writer.write("INSERT INTO " + tableName + "VALUES\n");
            List<String> valuesList = new ArrayList<>();
            for (String[] values : chunk) {
                StringBuilder sb = new StringBuilder("(");
                for (int j = 0; j < indices.size(); j++) {
                    String col = headers.get(j);
                    String val = indices.get(j) < values.length ? values[indices.get(j)]
                            .replace("'", "''").trim() : "";

                    if (NUMERIC_COLUMNS.contains(col.toLowerCase())) {
                        val = val.replaceAll("\\D", "");
                    }

                    if (MONEY_COLUMNS.contains(col.toLowerCase())) {
                        System.out.println(col.toLowerCase());
                        val = val.replaceAll("AED", "")
                                .replaceAll(",", "").trim();
                        float floatVal = Float.parseFloat(val);
                        val = String.format("%.2f", floatVal);
                    }

                    if (DATETIME_COLUMNS.contains(col.toLowerCase())) {
                        val = val.replaceAll("-- --", "");
                    }

                    sb.append(val.isEmpty() ? "NULL" : "N'" + val + "'");
                    if (j < indices.size() - 1) sb.append(", ");
                }
                if (add_lastModify) {
                    sb.append(", N'").append(java.time.LocalDateTime.now().toString()).append("'");
                }
                sb.append(")");
                valuesList.add(sb.toString());
            }
            writer.write(String.join(",\n", valuesList) + ";\n");
        }
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.showAndWait();
    }
}
