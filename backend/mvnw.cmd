@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM   https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM Apache Maven Wrapper startup script for Windows, version 3.3.2

@echo off
setlocal

set SCRIPT_DIR=%~dp0
set WRAPPER_PROPERTIES=%SCRIPT_DIR%.mvn\wrapper\maven-wrapper.properties

if not exist "%WRAPPER_PROPERTIES%" (
  echo ERROR: .mvn\wrapper\maven-wrapper.properties not found. 1>&2
  exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%A in ("%WRAPPER_PROPERTIES%") do (
  if "%%A"=="distributionUrl" set DIST_URL=%%B
)

if "%DIST_URL%"=="" (
  echo ERROR: distributionUrl not found in maven-wrapper.properties. 1>&2
  exit /b 1
)

for %%F in ("%DIST_URL%") do set DIST_FILE=%%~nxF
set MVN_BASENAME=%DIST_FILE:-bin.zip=%

set MAVEN_HOME_PARENT=%USERPROFILE%\.m2\wrapper\dists\%MVN_BASENAME%

if not exist "%MAVEN_HOME_PARENT%\bin\mvn.cmd" (
  echo Downloading %MVN_BASENAME% ...
  if not exist "%MAVEN_HOME_PARENT%" mkdir "%MAVEN_HOME_PARENT%"
  powershell -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%MAVEN_HOME_PARENT%\_download.zip'"
  powershell -Command "Expand-Archive -Path '%MAVEN_HOME_PARENT%\_download.zip' -DestinationPath '%USERPROFILE%\.m2\wrapper\dists\'"
  del "%MAVEN_HOME_PARENT%\_download.zip"
)

"%MAVEN_HOME_PARENT%\bin\mvn.cmd" %*
endlocal
