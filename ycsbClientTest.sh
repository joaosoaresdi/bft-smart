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

if [ "$#" -lt 2 ]; then
    echo "Illegal number of parameters"
    echo "Usage: ./ycsbClientTest <# of clients> <base client id>"
    exit
fi

OP_COUNT=533
if [ "$#" -eq 3 ]; then
	OP_COUNT=$3
fi

CLIENTS=$1
CLIENT_ID=$2

while [ $CLIENTS -gt 0 ]
do
	CRT_CLIENT=$((CLIENT_ID + CLIENTS))
	java -Dlogback.configurationFile="./config/logback.xml" -cp .:./bin:./lib/* bftsmart.demo.ycsb.YCSBClient $CRT_CLIENT $OP_COUNT &
	((CLIENTS--))
done
