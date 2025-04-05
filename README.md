# DepChain

This repository contains the code and report of the project made by Group 15.

Team Members:
- Vasco Rodrigues das Neves de Magalhães e Silva (99132)
- Miguel dos Santos Raposo (112167)
- André Madaleno Gonçalves (112294)

``src`` is where the source code is located. ``docs`` is where the reports are located.


## How to run

To build the project, execute the following command:
```shell
mvn clean compile
```

To run the system normally, you need to create 1 Client and 4 Servers. You can create each process by inputting the following command:

Client:
```shell
mvn exec:java -Dexec.mainClass="client.app.ClientApp" -Dexec.args="<client-port> <address> <server-client-base-port>"
```
\<address\> should be either deaddeaddeaddeaddeaddeaddeaddeaddeaddead or beefbeefbeefbeefbeefbeefbeefbeefbeefbeef.
deaddeaddeaddeaddeaddeaddeaddeaddeaddead is the owner of the ISTCoin smart contract, so they are the only ones that can modify the blacklist. Additionally, the total supply of ISTCoin is initially owned by them.

Server:
```shell
mvn exec:java -Dexec.mainClass="server.app.Server" -Dexec.args="<server-base-port> <replica-id> <client-base-port>"
```

\<replica-id\> is an identifier of each process. They should be "p0", "p1", "p2" or "p3".
\<client-base-port\> must be the same as the client-port.
\<server-base-port\> must be the same across all servers.
\<server-client-base-port\> must be equal to \<server-base-port\> + 100.

If you're running it on Windows, ``exec.mainClass`` and ``exec.args`` in quotation marks:
```shell
mvn exec:java -D"exec.mainClass"="client.app.ClientApp" -D"exec.args"="<client-port> <address> <server-base-port>"
mvn exec:java -D"exec.mainClass"="server.app.Server" -D"exec.args"="<server-base-port> <replica-id> <client-base-port>"
```

To execute the tests, execute the following command:
```shell
mvn test
```