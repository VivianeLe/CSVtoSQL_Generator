@echo off
set FX="C:\Program Files\Java\javafx-sdk-24\lib"

echo Compiling Java files...
for /r %%f in (*.java) do (
    javac --module-path %FX% --add-modules javafx.controls,javafx.fxml -d out %%f
)

echo Running JavaFX app...
java --module-path %FX% --add-modules javafx.controls,javafx.fxml -cp out src/main/java/com/example/demo/SQLGenApplication.java

pause