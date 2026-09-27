# DB test cục bộ (postgres tạm, bảng users có 8 dòng)
java -jar /home/namnv/IdeaProjects/naviq/target/sqlctx-1.0.0.jar --host=127.0.0.1 --port=54329 --dbname=naviq --user=tester --password=x

# DB thật:
# java -jar /home/namnv/IdeaProjects/naviq/target/sqlctx-1.0.0.jar --host=10.1.9.2 --port=5432 --dbname=pawn --user=devops --password=Abc@123456

# Oracle test (container Docker "oracle-naviq", bảng NAVIQ.USERS có 8 dòng)
# java -jar /home/namnv/IdeaProjects/naviq/target/sqlctx-1.0.0.jar --dialect=oracle --host=127.0.0.1 --port=1521 --dbname=FREEPDB1 --user=naviq --password=Naviq123
