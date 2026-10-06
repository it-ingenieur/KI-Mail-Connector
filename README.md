# KI-Mail-Connector

Kleines Java-21-Referenzprojekt für die Verbindung eines dedizierten E-Mailkontos mit ChatGPT über MCP.

Das Projekt verfolgt zwei Ziele:

1. eine möglichst kleine, praktisch nutzbare Mail-Anbindung;
2. ein nachvollziehbares Lernprojekt für Java, IMAP, MIME, JSON-RPC und MCP.

Der Quelltext ist deshalb bewusst ausführlich auf Deutsch kommentiert.

## Architektur

```text
ChatGPT / MCP-Client
        |
        | MCP über HTTP + JSON-RPC
        v
McpHttpServer
        |
        | MailGateway
        v
ImapMailGateway
        |
        | IMAPS/TLS
        v
dediziertes Mailkonto
```

### Warum ein MailGateway-Interface?

`McpHttpServer` soll nichts über IMAP wissen. Er kennt nur drei fachliche Operationen:

- Nachrichten suchen,
- Nachricht lesen,
- Entwurf erzeugen.

Die konkrete Technik dahinter steckt in `ImapMailGateway`. Dadurch kann die MCP-Schicht im Unit-Test mit einem Fake getestet werden, ohne einen echten Mailserver zu benötigen.

## Scope 0.1

Öffentlich verfügbare MCP-Tools:

- `search_mail` – Mails suchen bzw. auflisten
- `read_mail` – eine komplette Mail über Ordner + IMAP-UID lesen
- `create_draft` – einen Entwurf im Drafts-Ordner speichern

### Bewusst nicht vorhanden: send_mail

Version 0.1 enthält **kein** `send_mail`-Tool.

Entwürfe werden mit IMAP `APPEND` in den Drafts-Ordner geschrieben. Dafür ist SMTP nicht erforderlich. Ein späterer Versand kann architektonisch ergänzt werden, ohne die heutige Sicherheitsgrenze aufzuweichen.

## Warum Ordner + UID?

Eine IMAP-UID identifiziert eine Nachricht stabil **innerhalb eines Ordners**. Sie ist nicht global über das gesamte Konto eindeutig.

Deshalb liefert `search_mail` immer:

```text
folder + uid
```

und `read_mail` erwartet genau diese Kombination.

## MIME kurz erklärt

Eine E-Mail ist technisch nicht einfach ein Textblock. Sie kann aus verschachtelten MIME-Teilen bestehen:

```text
multipart/mixed
  +-- multipart/alternative
  |     +-- text/plain
  |     +-- text/html
  +-- application/pdf
```

`ImapMailGateway.collectPart(...)` durchläuft diesen Baum rekursiv.

Version 0.1 liefert:

- Plain-Text-Inhalt,
- HTML-Inhalt,
- Metadaten der Anhänge.

Binärdaten der Anhänge werden noch nicht übertragen.

## MCP kurz erklärt

MCP – Model Context Protocol – ermöglicht einem KI-Client, Werkzeuge eines externen Servers zu entdecken und aufzurufen.

### Zwei Protokoll-Ären

Das Projekt unterstützt bewusst beide derzeit relevanten MCP-Lebenszyklen.

Legacy bis einschließlich `2025-11-25`:

```text
initialize
    |
tools/list
    |
tools/call
```

Modern ab `2026-07-28`:

```text
server/discover
    |
tools/list
    |
tools/call
```

Der wesentliche Unterschied: Die moderne Revision besitzt keinen dauerhaften
Initialize-Handshake und keine MCP-Session mehr. Jeder Request trägt seine
Protokollversion und Client-Capabilities im `_meta`-Block selbst.

Bei Streamable HTTP werden wichtige Routing-Informationen zusätzlich in Header
gespiegelt:

```text
MCP-Protocol-Version
Mcp-Method
Mcp-Name            (z. B. bei tools/call)
```

Der `McpHttpServer` prüft diese Spiegelung bei modernen HTTP-Requests.

### Warum server/discover nur 2026-07-28 meldet

`server/discover` gehört selbst zur modernen MCP-Ära. Deshalb meldet
`supportedVersions` nur Revisionen, die pro Request funktionieren.

Die Legacy-Versionen bleiben trotzdem unterstützt. Sie werden über
`initialize` ausgehandelt und deshalb nicht in `server/discover` angeboten.

### Moderne Resultate

Resultate der Revision `2026-07-28` enthalten zusätzlich:

- `resultType: "complete"`
- Serveridentität in `_meta["io.modelcontextprotocol/serverInfo"]`
- bei cachebaren Listen/Discovery-Antworten `ttlMs` und `cacheScope`

Für dieses kleine Projekt verwenden wir konservativ:

```text
ttlMs      = 0
cacheScope = private
```

Damit darf ein Client die Antwort technisch speichern, betrachtet sie aber
sofort wieder als veraltet und teilt sie nicht zwischen verschiedenen Nutzern.

Wichtig für dieses Projekt: Die Tool-Liste ist zugleich eine Berechtigungsgrenze.
Da kein `send_mail` veröffentlicht wird, gibt es keinen regulären MCP-Aufruf
zum Versenden einer Mail.

## Abhängigkeiten

Das Projekt hält die Zahl externer Bibliotheken bewusst klein:

- Jakarta Mail / Eclipse Angus – IMAP und MIME
- Jackson – JSON
- JUnit 5 – Tests

Der HTTP-Server stammt direkt aus dem JDK (`com.sun.net.httpserver.HttpServer`). Ein Spring-/Jakarta-Webframework wäre für Version 0.1 unnötige Komplexität.

## Voraussetzungen

- JDK 21
- Maven 3.9+
- IMAP-Server mit TLS/IMAPS
- dediziertes Mailkonto

## Konfiguration

Siehe `.env.example`.

Erforderlich:

- `MAIL_IMAP_HOST`
- `MAIL_USERNAME`
- `MAIL_PASSWORD`

Optional:

- `MAIL_IMAP_PORT` – Standard 993
- `MAIL_FROM` – Standard `MAIL_USERNAME`
- `MAIL_DRAFTS_FOLDER` – Standard `Drafts`
- `MCP_BIND` – Standard `127.0.0.1`
- `MCP_PORT` – Standard `8080`

Reale Zugangsdaten gehören **niemals** in Git.

## Start

In IntelliJ:

`com.fourwt.mailconnector.KiMailConnectorApplication` starten.

Oder über Maven:

```bash
mvn clean test
mvn exec:java -Dexec.mainClass=com.fourwt.mailconnector.KiMailConnectorApplication
```

Der lokale MCP-Endpunkt lautet standardmäßig:

```text
http://127.0.0.1:8080/mcp
```

## Sicherheitsgrenze

- Mailpasswort bleibt ausschließlich in der Prozessumgebung.
- Der MCP-Server bindet standardmäßig nur an localhost.
- Lesen erfolgt über IMAPS/TLS.
- Entwürfe werden per IMAP gespeichert.
- Es existiert kein öffentliches Versandwerkzeug.
- SMTP wird in Version 0.1 nicht benötigt.

## Tests als Lernmaterial

`McpHttpServerTest` zeigt, wie eine Protokollschicht ohne echten Mailserver getestet werden kann:

```text
McpHttpServer
      |
      v
FakeMailGateway
```

Das Fake implementiert dasselbe `MailGateway`-Interface wie der reale IMAP-Adapter, führt aber keinerlei Netzwerkzugriffe aus.

## Aktueller Teststand

Gegen ein reales dediziertes Mailkonto wurden bereits erfolgreich geprüft:

1. IMAPS-Verbindung,
2. Suche im Posteingang,
3. Lesen einer Nachricht über Ordner + UID,
4. Plain-Text- und HTML-MIME-Inhalte,
5. Erkennung von PDF- und vCard-Anhängen,
6. Lesen ohne Änderung des IMAP-Flags `\\Seen`,
7. Erzeugen eines Entwurfs im Drafts-Ordner.

Zusätzlich prüft die CI beide MCP-Lebenszyklen mit Unit-Tests.

## Moderne MCP-Anfrage per curl

Ein `server/discover`-Aufruf für die Revision `2026-07-28` sieht über HTTP
beispielsweise so aus:

```bash
curl -s \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: server/discover' \
  -d '{
    "jsonrpc":"2.0",
    "id":"discover-1",
    "method":"server/discover",
    "params":{
      "_meta":{
        "io.modelcontextprotocol/protocolVersion":"2026-07-28",
        "io.modelcontextprotocol/clientCapabilities":{}
      }
    }
  }' \
  http://127.0.0.1:8080/mcp
```

Ein älterer Client kann weiterhin den klassischen `initialize`-Handshake
verwenden.
