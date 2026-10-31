# Googol — Relatório da Meta 1

**Sistemas Distribuídos 2026/27** · Universidade de Coimbra

| Nome | Número |
|---|---|
| Daniela Ramos Dias | 2024209417 |
| Diogo Filipe Samouco Silva | 2024216734 |

As instruções de instalação e execução estão no `README.md`.

---

## 1. Arquitetura de software

O sistema é composto por quatro programas Java que comunicam exclusivamente por RMI.

```
                 Cliente (ClientMenu)
                        │  pesquisas, indexar URL, estatísticas (callback)
                        ▼
   Downloaders ──► Gateway  (registry RMI · fila de URLs · lista de Barrels)
        │  takeURL / addURLs     │  pesquisas (round-robin + failover)
        │                        ▼
        └── reliable multicast ──► Storage Barrels (barrel1, barrel2)
```

- **Gateway** — único ponto de contacto dos clientes. Não guarda o índice: reencaminha pesquisas para um Barrel ativo. Contém também a **fila de URLs** e a lista de Barrels ativos (os Barrels registam-se nela).
- **Storage Barrel** — réplica do índice. Recebe páginas dos Downloaders e responde a pesquisas.
- **Downloader** — várias threads em paralelo; cada uma retira um URL da fila, analisa a página com o jsoup e envia-a a todos os Barrels.
- **Cliente** — interface de texto que só invoca métodos remotos da Gateway.

**Organização do código** (`src/googol/`):

| Pacote | Conteúdo |
|---|---|
| `common` | Interfaces remotas; classes serializáveis (`PageData`, `IndexMessage`, `SearchPage`, `SearchResult`, `SystemStats`); `Config` (leitura do `.properties`); `Persistence` (gravação atómica) |
| `gateway` | `Gateway` |
| `barrel` | `Barrel` (parte RMI) e `BarrelStorage` (índice, pesquisa e persistência, sem RMI) |
| `downloader` | `Downloader`, `PageParser` (jsoup) e `ReliableMulticast` |
| `client` | `Client` (comunicação e retry) e `ClientMenu` (interface) |

A lógica está separada da parte RMI, para poder ser testada isoladamente. A configuração está em ficheiros `.properties`.

**Barrel:** índice invertido `palavra → {URLs}`; backlinks `URL → {URLs que apontam para ele}`; `URL → (título, citação)`. A pesquisa faz a interseção dos URLs de cada termo, ordena por número de backlinks (desempate pelo URL, para a ordem ser estável entre páginas) e divide em páginas de 10.

## 2. Funcionamento do RMI

### 2.1 Interfaces remotas

| Interface | Implementada por | Usada por | Métodos |
|---|---|---|---|
| `GatewayInterface` | Gateway | Cliente | `indexURL`, `search`, `getBacklinks`, `getStats`, `subscribeStats`, `unsubscribeStats` |
| `QueueInterface` | Gateway | Downloaders, Barrels | `takeURL`, `addURLs`, `registerBarrel`, `getBarrels` |
| `BarrelInterface` | Barrel | Downloaders, Gateway | `receive`, `search`, `getBacklinks`, `getIndexSize`, `ping` |
| `StatsCallback` | Cliente | Gateway | `onStatsUpdate` |

O único registry RMI é criado pela Gateway. Os Barrels não se registam no registry: enviam a sua referência remota à Gateway (`registerBarrel`) e repetem esse registo a cada 5 s; os Downloaders obtêm as referências com `getBarrels`. Assim, se a Gateway reiniciar, volta a conhecer os Barrels sem intervenção.

**Fila de URLs:** `takeURL` é `synchronized` e retira o URL da fila, pelo que cada URL é entregue a um só Downloader. Os URLs pedidos pelo utilizador entram no início da fila; os encontrados nas páginas, no fim. Um conjunto de URLs conhecidos impede visitas repetidas.

### 2.2 Callbacks (estatísticas em tempo real)

O Cliente exporta um objeto `StatsCallback` e entrega-o à Gateway com `subscribeStats`. A Gateway recalcula as estatísticas (10 pesquisas mais comuns, Barrels ativos com tamanho do índice, tempo médio de resposta por Barrel em décimas de segundo) após cada pesquisa ou alteração dos Barrels, e verifica a cada 2 s se estes estão vivos. **Só as envia quando são diferentes** das últimas enviadas, numa thread própria para não atrasar as pesquisas.

### 2.3 Failover

- **Gateway → Barrels:** os pedidos são distribuídos por *round-robin*. Se o Barrel escolhido lançar `RemoteException`, é retirado da lista e o mesmo pedido é feito ao seguinte; o cliente só recebe erro se todos falharem.
- **Cliente → Gateway:** perante uma `RemoteException`, o Cliente volta a procurar a Gateway no registry e repete a operação (5 tentativas, 2 s entre elas). Reconfirma também a subscrição das estatísticas a cada 3 s; como `subscribeStats` é idempotente, só tem efeito se a Gateway tiver reiniciado.
- **Recuperação da Gateway:** a fila, os URLs conhecidos e a contagem de pesquisas são gravados em disco (os pedidos do utilizador de imediato, o resto a cada segundo).

## 3. Replicação: reliable multicast

O algoritmo está em `ReliableMulticast` (Downloader) e `BarrelStorage` (Barrel).

1. **Grupo fixo.** O grupo de destino é definido na configuração (`downloader.barrels=barrel1,barrel2`). Cada Downloader mantém, para cada Barrel do grupo, uma **fila FIFO de mensagens pendentes**.
2. **Identificação.** Cada mensagem leva o par (*id da sessão*, *número de sequência*). O id da sessão inclui o instante de arranque do Downloader (ex.: `downloader1@1793217413479`), para que, após um reinício, os números recomeçados em 1 não sejam confundidos com mensagens antigas.
3. **Envio.** Cada mensagem é posta na fila de **todos** os Barrels e cada fila é esvaziada por ordem.
4. **ACK.** Uma mensagem só sai da fila quando `receive()` retorna sem erro. O Barrel só retorna depois de gravar a mensagem em disco.
5. **Falha por omissão.** Se `receive()` lançar `RemoteException`, a mensagem fica na fila desse Barrel; os restantes não são afetados.
6. **Retransmissão.** A cada 2 s, o Downloader pede à Gateway as referências atualizadas (um Barrel reiniciado tem uma referência nova) e reenvia as pendentes, pela ordem original. Se a Gateway estiver em baixo, usa as referências que já conhecia.
7. **Duplicados.** Se um ACK se perder, a mensagem é reenviada; o Barrel deteta-a pelo par (sessão, sequência) e ignora-a.

Assim, todos os Barrels recebem todas as mensagens de cada Downloader, pela ordem de envio, desde que voltem a ficar ativos.

**Persistência do Barrel.** Cada mensagem é acrescentada a um registo (*log*) com `fsync` antes do ACK; a cada 200 mensagens grava-se o índice completo (escrita atómica) e o registo é esvaziado. No arranque, o Barrel lê o índice e reaplica o registo, ignorando um último registo incompleto. Com 19 000 palavras, cada página demora 0,9 ms a gravar (526 ms se o índice inteiro fosse gravado a cada página).

**Limitações.** As mensagens pendentes ficam na memória do Downloader: se este avariar enquanto um Barrel está em baixo, esse Barrel não as recebe. Um URL retirado da fila por um Downloader que avarie antes de o processar perde-se.

## 4. Distribuição de tarefas

Seguimos a primeira divisão proposta no enunciado:

| Elemento | Responsabilidade |
|---|---|
| Daniela Ramos Dias (2024209417) | Gateway e componente RMI dos Barrels |
| Diogo Filipe Samouco Silva (2024216734) | Downloaders e componente de reliable multicast dos Barrels |

Na prática, os dois elementos acompanharam o desenvolvimento de todos os componentes e realizaram os testes em conjunto.

## 5. Testes

Os testes automáticos (`BarrelTeste`, `GatewayTeste`, `DownloaderTeste`, `ClientTeste`; 70 no total) usam RMI real, um servidor Web local e avarias simuladas. Os testes manuais foram feitos em duas máquinas.

| # | Descrição | Tipo | Resultado |
|---|---|---|---|
| 1 | Pesquisa devolve só as páginas com todos os termos (sem distinguir maiúsculas nem pontuação) | Auto | Pass |
| 2 | Resultados ordenados por número de ligações recebidas | Auto | Pass |
| 3 | Resultados agrupados de 10 em 10, com navegação | Auto | Pass |
| 4 | Consulta das páginas com ligação para um URL | Auto | Pass |
| 5 | Mensagem repetida (retransmissão) é ignorada pelo Barrel | Auto | Pass |
| 6 | Barrel recupera o índice após reinício, incluindo registo com escrita incompleta | Auto | Pass |
| 7 | `--reset` apaga os dados de teste | Auto | Pass |
| 8 | Fila: URL do utilizador tem prioridade e é gravado de imediato; repetidos e URLs inválidos rejeitados | Auto | Pass |
| 9 | Cada URL é entregue a um só Downloader | Auto | Pass |
| 10 | Pesquisas distribuídas pelos dois Barrels (round-robin) | Auto | Pass |
| 11 | Barrel avariado: pesquisas continuam no outro e o avariado é retirado | Auto | Pass |
| 12 | Gateway recupera a fila e as pesquisas após reinício | Auto | Pass |
| 13 | Multicast: mensagens para Barrel avariado ficam pendentes e são entregues por ordem quando recupera | Auto | Pass |
| 14 | Sistema completo: indexação iterativa a partir de um URL; Barrels com informação idêntica | Auto | Pass |
| 15 | Cliente conclui a operação após a Gateway reiniciar (retry) e retoma as estatísticas | Auto | Pass |
| 16 | Estatísticas enviadas por callback só quando mudam | Auto | Pass |
| 17 | Execução em duas máquinas: os dois Downloaders indexam em paralelo URLs diferentes | Manual | Pass |
| 18 | Duas máquinas: barrel1 desligado → pesquisas no barrel2; ao reiniciar, recebe as páginas em falta | Manual | Pass |
| 19 | Duas máquinas: Gateway desligada durante uma pesquisa → o cliente repete e conclui | Manual | Pass |
| 20 | Duas máquinas: estatísticas atualizadas no cliente da máquina #2 | Manual | Pass |
