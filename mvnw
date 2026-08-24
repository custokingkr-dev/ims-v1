#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# Apache Maven Wrapper startup script, version 3.3.2
# Reads .mvn/wrapper/maven-wrapper.properties, downloads the pinned Maven
# distribution on first run, then delegates all arguments to it.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_PROPERTIES="${SCRIPT_DIR}/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "${WRAPPER_PROPERTIES}" ]; then
  echo "ERROR: .mvn/wrapper/maven-wrapper.properties not found." >&2
  exit 1
fi

DIST_URL=$(grep '^distributionUrl=' "${WRAPPER_PROPERTIES}" | cut -d'=' -f2-)

if [ -z "${DIST_URL}" ]; then
  echo "ERROR: distributionUrl not found in maven-wrapper.properties." >&2
  exit 1
fi

MVN_BASENAME=$(basename "${DIST_URL}" -bin.zip)
MAVEN_HOME_PARENT="${HOME}/.m2/wrapper/dists/${MVN_BASENAME}"

if [ ! -x "${MAVEN_HOME_PARENT}/bin/mvn" ]; then
  echo "Downloading ${MVN_BASENAME} ..."
  mkdir -p "${MAVEN_HOME_PARENT}"
  TMPZIP="${MAVEN_HOME_PARENT}/_download.zip"
  if command -v curl > /dev/null 2>&1; then
    curl -fsSL -o "${TMPZIP}" "${DIST_URL}"
  elif command -v wget > /dev/null 2>&1; then
    wget -q -O "${TMPZIP}" "${DIST_URL}"
  else
    echo "ERROR: Neither curl nor wget is available." >&2
    exit 1
  fi
  unzip -q "${TMPZIP}" -d "${HOME}/.m2/wrapper/dists/"
  rm -f "${TMPZIP}"
fi

exec "${MAVEN_HOME_PARENT}/bin/mvn" "$@"
