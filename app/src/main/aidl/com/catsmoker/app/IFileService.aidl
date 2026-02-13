package com.catsmoker.app;

interface IFileService {
    void destroy();
    int executeCommand(in String[] command);
}