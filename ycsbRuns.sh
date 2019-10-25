#0.6 GBytes state
if [ $# -eq 1 ]; then
	RECORDS=$1
	./ycsbClient.sh $RECORDS 1 > "${RECORDS}_1.out" 2> "${RECORDS}_1.err"; \
	sleep 5; \
	./ycsbClient.sh $RECORDS 2 >  "${RECORDS}_2.out" 2> "${RECORDS}_2.err"; \
	sleep 5; \
	./ycsbClient.sh $RECORDS 4 >  "${RECORDS}_4.out" 2> "${RECORDS}_4.err"; \
	sleep 5; \
	./ycsbClient.sh $RECORDS 8 >  "${RECORDS}_8.out" 2> "${RECORDS}_8.err"; \
	sleep 5; \
	./ycsbClient.sh $RECORDS 16 >  "${RECORDS}_16.out" 2> "${RECORDS}_16.err"; \
	sleep 5; \
	./ycsbClient.sh $RECORDS 30 >  "${RECORDS}_30.out" 2> "${RECORDS}_30.err"; \
else
    echo "Missing arguments"
    echo "Usage: ycsbRuns.sh recordcount"
fi


# #1.2 GBytes state
#
#RECORDS=120000
#./ycsbClient.sh $RECORDS 1 > "${RECORDS}_1.out" 2> "${RECORDS}_1.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 2 >  "${RECORDS}_2.out" 2> "${RECORDS}_2.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 4 >  "${RECORDS}_4.out" 2> "${RECORDS}_4.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 8 >  "${RECORDS}_8.out" 2> "${RECORDS}_8.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 16 >  "${RECORDS}_16.out" 2> "${RECORDS}_16.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 30 >  "${RECORDS}_30.out" 2> "${RECORDS}_30.err"; \
#
# #1.8 GBytes state
#
#RECORDS=180000
#./ycsbClient.sh $RECORDS 1 > "${RECORDS}_1.out" 2> "${RECORDS}_1.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 2 >  "${RECORDS}_2.out" 2> "${RECORDS}_2.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 4 >  "${RECORDS}_4.out" 2> "${RECORDS}_4.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 8 >  "${RECORDS}_8.out" 2> "${RECORDS}_8.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 16 >  "${RECORDS}_16.out" 2> "${RECORDS}_16.err"; \
#sleep 5; \
#./ycsbClient.sh $RECORDS 30 >  "${RECORDS}_30.out" 2> "${RECORDS}_30.err"; \
