# Googol — Motor de pesquisa de páginas Web

Projeto de **Sistemas Distribuídos 2026/27 — Meta 1** (Universidade de Coimbra).

O Googol indexa páginas Web automaticamente (Web crawler) e permite pesquisá-las.
É composto por quatro programas Java que comunicam por **RMI**:

| Programa | Classe principal | Função |
|---|---|---|
| **Gateway** | `googol.gateway.Gateway` | Porta de entrada dos clientes; guarda a fila de URLs; escolhe um Barrel para cada pesquisa (com failover) |
| **Storage Barrel** | `googol.barrel.Barrel` | Guarda o índice invertido (replicado: 2 Barrels com a mesma informação) |
| **Downloader** | `googol.downloader.Downloader` | Descarrega páginas (jsoup) e envia-as aos Barrels por *reliable multicast* |
| **Cliente** | `googol.client.ClientMenu` | Interface de texto para os utilizadores |

---

## 1. Requisitos

- **Java JDK 25** (por exemplo, Eclipse Temurin 25: <https://adoptium.net>).
  Confirmar com `javac -version` → deve mostrar `javac 25`.
  ⚠️ Todas as máquinas têm de usar a **mesma versão** de Java.
- A biblioteca **jsoup** já vem incluída em `lib/` (não é preciso descarregar nada).

## 2. Estrutura das pastas

```
googol/
├── compilar.bat          compila tudo (Windows)
├── README.md             este ficheiro
├── config/
│   ├── config.properties    execução num só PC (valor por omissão)
│   ├── barrel2.properties   2.º Barrel num só PC
│   ├── maquina1.properties  execução em 2 máquinas: máquina #1
│   └── maquina2.properties  execução em 2 máquinas: máquina #2
├── lib/                  jsoup
├── src/googol/
│   ├── common/           interfaces remotas e classes partilhadas
│   ├── gateway/          Gateway
│   ├── barrel/           Storage Barrel
│   ├── downloader/       Downloader e reliable multicast
│   └── client/           Cliente
├── out/                  (criada ao compilar)
└── data/                 (criada ao executar: dados dos Barrels e da Gateway)
```

## 3. Compilar

**Todos os comandos deste ficheiro são escritos dentro da pasta `googol`.**

Windows (PowerShell ou Linha de Comandos):
```
.\compilar.bat
```
Deve aparecer `Compilacao concluida com sucesso.`

Linux / macOS:
```
javac -encoding UTF-8 -cp "lib/*" -d out $(find src/googol -name "*.java")
```

> Nos comandos `java` seguintes, o *classpath* é `"out;lib\*"` no Windows.
> Em Linux/macOS usa-se `"out:lib/*"` (dois pontos e barra normal).

## 4. Configuração

Toda a configuração está em ficheiros `.properties` na pasta `config/`.
**Alterá-los não exige recompilar.** O ficheiro é indicado como argumento
(ex.: `config\maquina1.properties`); sem argumento usa-se `config/config.properties`.

Chaves principais:

| Chave | Significado |
|---|---|
| `rmi.hostname` | IP **desta** máquina na rede (os outros PCs usam-no para a contactar) |
| `gateway.host` / `gateway.port` / `gateway.name` | Onde está a Gateway |
| `barrel.id` / `barrel.data.file` | Nome deste Barrel e ficheiro onde guarda o índice |
| `downloader.id` / `downloader.threads` | Nome deste Downloader e nº de downloads em paralelo |
| `downloader.barrels` | Barrels que têm de receber todas as páginas (`barrel1,barrel2`) |
| `client.retry.attempts` / `client.retry.delay.ms` | Tentativas do cliente quando a Gateway falha |
| `gateway.save.interval.ms` | De quanto em quanto tempo a Gateway grava a fila (opcional; 1000 ms por omissão) |

## 5. Executar num só PC

Abrir **5 janelas** (todas na pasta `googol`) e arrancar **por esta ordem**:

| # | Comando | Mensagem de confirmação |
|---|---|---|
| 1 | `java -cp "out;lib\*" googol.gateway.Gateway --reset` | `[Gateway] Pronta no porto 1099` |
| 2 | `java -cp "out;lib\*" googol.barrel.Barrel --reset` | `[barrel1] Registado na Gateway` |
| 3 | `java -cp "out;lib\*" googol.barrel.Barrel config\barrel2.properties --reset` | `[barrel2] Registado na Gateway` |
| 4 | `java -cp "out;lib\*" googol.downloader.Downloader` | `[downloader1] Pronto com 2 threads` |
| 5 | `java -cp "out;lib\*" googol.client.ClientMenu` | Menu do Googol |

### Apagar os dados de teste (`--reset`)

A opção `--reset` na Gateway e nos Barrels **apaga os dados guardados** e começa
com o sistema vazio. Para manter os dados de uma execução para a seguinte,
arrancar **sem** `--reset`.

## 6. Executar em duas máquinas

| Máquina | Corre | Ficheiro de configuração |
|---|---|---|
| **#1** | Gateway, barrel1, downloader1 | `config\maquina1.properties` |
| **#2** | barrel2, downloader2, Cliente | `config\maquina2.properties` |

### 6.1 Preparar a rede (nas duas máquinas)

1. Ligar as duas máquinas à **mesma rede** (recomenda-se o hotspot de um telemóvel;
   redes como a eduroam podem bloquear a comunicação entre computadores).
2. Marcar a rede como **Privada**: Definições → Rede e Internet → Wi-Fi →
   (nome da rede) → Tipo de perfil de rede → **Privada**.
3. Permitir o Java na Firewall: procurar "Permitir uma aplicação através da Firewall
   do Windows" → **Alterar definições** → marcar **Privada** em
   **OpenJDK Platform binary**. (Se não aparecer: *Permitir outra aplicação* →
   `C:\Program Files\Eclipse Adoptium\jdk-25...\bin\java.exe`.)
4. Ver o IP de cada máquina com `ipconfig` (linha **Endereço IPv4**).

### 6.2 Escrever os IPs nos ficheiros de configuração

| Ficheiro | `rmi.hostname` | `gateway.host` |
|---|---|---|
| `maquina1.properties` | IP da máquina #1 | IP da máquina #1 |
| `maquina2.properties` | IP da máquina #2 | IP da máquina #1 |

### 6.3 Arrancar

**Máquina #1** (por esta ordem):
```
java -cp "out;lib\*" googol.gateway.Gateway config\maquina1.properties --reset
java -cp "out;lib\*" googol.barrel.Barrel config\maquina1.properties --reset
java -cp "out;lib\*" googol.downloader.Downloader config\maquina1.properties
```

**Verificar a ligação** (na máquina #2, no PowerShell):
```
Test-NetConnection <IP da máquina #1> -Port 1099
```
Tem de mostrar `TcpTestSucceeded : True`.

**Máquina #2** (por esta ordem):
```
java -cp "out;lib\*" googol.barrel.Barrel config\maquina2.properties --reset
java -cp "out;lib\*" googol.downloader.Downloader config\maquina2.properties
java -cp "out;lib\*" googol.client.ClientMenu config\maquina2.properties
```
Na janela da Gateway (máquina #1) deve aparecer `Barrel registado: barrel2`.

## 7. Utilizar o Cliente

| Opção | Função |
|---|---|
| `1` | Indexar um URL (ex.: `https://www.uc.pt`) |
| `2` | Pesquisar. Nos resultados: `s` página seguinte, `a` anterior, `l N` páginas com ligação para o resultado N, `v` voltar |
| `3` | Páginas com ligação para um URL |
| `4` | Estatísticas em tempo real (atualizam sozinhas; Enter para voltar) |
| `0` | Sair |

## 8. Testes automáticos

Depois de compilar:
```
java -cp "out;lib\*" googol.barrel.BarrelTeste
java -cp "out;lib\*" googol.gateway.GatewayTeste
java -cp "out;lib\*" googol.downloader.DownloaderTeste
java -cp "out;lib\*" googol.client.ClientTeste
```
Cada um termina com `Total: N PASS, 0 FAIL` (14 + 25 + 20 + 11 = 70 testes).
Os testes usam portos próprios (1196–1199) e pastas temporárias: **não interferem**
com os dados do sistema, mas não devem correr ao mesmo tempo que a Gateway
se esta usar um desses portos.

## 9. Resolução de problemas

| Sintoma | Causa provável / solução |
|---|---|
| `compilar.bat is not recognized` | Não está na pasta `googol` (`cd` para lá) |
| `UnsupportedClassVersionError` | Versão de Java diferente entre máquinas → instalar Java 25 em todas |
| `Gateway indisponível` repetido | Gateway não arrancou, ou `gateway.host`/`gateway.port` errados |
| `Erro ao arrancar: ... Port already in use` | Já há uma Gateway a correr (fechar a outra janela) |
| `Test-NetConnection` dá `False` | Firewall ou rede pública → ver secção 6.1 |
| Barrel da outra máquina regista-se mas não responde | `rmi.hostname` dessa máquina errado (tem de ser o IP dela, não `localhost`) |
| `barrel2 ainda não está disponível` no Downloader | Normal enquanto o barrel2 não arranca; as páginas ficam guardadas e são entregues quando ele aparecer |
