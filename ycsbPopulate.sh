# Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#java -Dlogback.configurationFile="./config/logback.xml" -Xms64g -Xmx100g -cp ./lib/*:./bin/ com.yahoo.ycsb.Client -load -P config/workloads/workloada -db bftsmart.demo.ycsb.YCSBClient

#/bin/bash
if [ $# -eq 1 ]; then
	java -Dlogback.configurationFile="./config/logback.xml" -Xms64g -Xmx120g -cp .:./lib/*:./bin com.yahoo.ycsb.Client -load -P config/workloads/workloada -p recordcount=$1 -db bftsmart.demo.ycsb.YCSBClient
else
    echo "Missing arguments"
    echo "Usage: ycsbPopulate.sh recordcount"
fi
