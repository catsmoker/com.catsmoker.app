package com.catsmoker.app.shizuku;

import com.catsmoker.app.shizuku.CommandResult;

interface ICommandRunner {
    String executeCommand(String command);
    CommandResult executeCommandWithResult(String command);
    String getThermalTemperatures();
    void killCurrentProcess();
    void destroy();
}
