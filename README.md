Installation guide for local Docker postgres DB:

open Docker

run the docker-compose.yml scrip or execute this command in cmd of the project directory:

docker-compose up -d

to access view using pgAdmin4:

open pgAdmin

add a new server:
host: localhost
port: 5432
database: ismd_tool_db
username: ismd_user
password: ismd_password
