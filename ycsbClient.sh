#/bin/bash
if [ $# -eq 2 ]; then
	INIT_KEY=${HOSTNAME#*-}000
	java -Dlogback.configurationFile="./config/logback.xml" -Xms64g -Xmx120g -cp .:./lib/*:./bin com.yahoo.ycsb.Client -threads $2 -P config/workloads/workloada -p maxexecutiontime=180 -p recordcount=$1 -p measurementtype=timeseries -p smart-initkey=$INIT_KEY -p timeseries.granularity=1000 -db bftsmart.demo.ycsb.YCSBClient -s
else
    echo "Missing arguments"
    echo "Usage: ycsbClient.sh recordcount threads"
fi

