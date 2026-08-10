package com.catsmoker.app.shizuku;

import com.catsmoker.app.shizuku.CommandResult;
import com.catsmoker.app.shizuku.SuspendResult;

interface ICommandRunner {
    String executeCommand(String command);
    int executeCommandWithExitCode(String command);
    CommandResult executeCommandWithResult(String command);
    String readProcStat();
    String getThermalTemperatures();
    SuspendResult suspendPackages(in String[] packageNames, boolean suspended);
    int setAppOpMode(in String[] packageNames, int opCode, int mode);
    void killCurrentProcess();
    void destroy();
}
