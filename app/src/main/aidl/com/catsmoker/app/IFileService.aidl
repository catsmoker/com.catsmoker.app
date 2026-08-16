package com.catsmoker.app;

interface IFileService {
    void destroy();
    int executeCommand(in String[] command);
    List<String> executeAndGetOutput(in String[] command);
}
