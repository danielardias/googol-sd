@echo off
REM Compila todo o projeto Googol para a pasta "out".
REM Uso: duplo clique neste ficheiro, ou escrever "compilar" na linha de comandos dentro desta pasta.
cd /d "%~dp0"
javac -encoding UTF-8 -cp "lib\*" -d out src\googol\common\*.java src\googol\barrel\*.java src\googol\gateway\*.java src\googol\downloader\*.java src\googol\client\*.java
if errorlevel 1 (
    echo ERRO: a compilacao falhou.
) else (
    echo Compilacao concluida com sucesso.
)
