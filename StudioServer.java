/*
 * Sherva SERVER - THINK. WRITE. DESIGN. (single file, no GUI).
 * Run:  java StudioServer.java [port]        (default port 5050)
 *  or:  javac StudioServer.java && java StudioServer [port]
 * Contains: StudioServer (accept loop + validation), ClientHandler (thread per client),
 *           StudioState (ideas/feedback/revisions), Protocol (message format).
 */
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Sherva server. Accepts TCP clients (one handler thread each), validates every
 * command and mutates the shared StudioState while holding a single lock, then
 * broadcasts the result so every client stays synchronised.
 */
public class StudioServer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_.-]{1,16}");

    private final int port;
    private final Object lock = new Object();             // guards clients + state
    private final List<ClientHandler> clients = new ArrayList<>();
    private final StudioState state = new StudioState();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public StudioServer(int port) { this.port = port; }

    public static void main(String[] args) {
        int port = Protocol.DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                System.err.println("Usage: java inkloop.StudioServer [port 1-65535]");
                return;
            }
        }
        new StudioServer(port).start();
    }

    public void start() {
        try (ServerSocket ss = new ServerSocket(port)) {
            String ip;
            try { ip = InetAddress.getLocalHost().getHostAddress(); } catch (IOException e) { ip = "unknown"; }
            log("Sherva server listening on port " + port + " (this PC: " + ip
                    + " - clients on the LAN connect to your Wi-Fi IPv4 address)");
            startConsole();
            while (true) {
                Socket s = ss.accept();
                log("Connection from " + s.getRemoteSocketAddress());
                pool.execute(new ClientHandler(s, this));
            }
        } catch (IOException e) {
            System.err.println("Server stopped: " + e.getMessage() + " (is the port already in use?)");
        } finally {
            pool.shutdownNow();
        }
    }

    void log(String msg) { System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg); }

    // ------------------------------------------------------------------ join / leave
    String register(ClientHandler c, String nick) {
        synchronized (lock) {
            String err = validateNick(nick, c);
            if (err != null) return err;
            c.setNick(nick);
            clients.add(c);
            c.send(Protocol.build("JOINED", nick));
            c.send("CLEAR");
            for (String l : state.snapshot()) c.send(l);
            broadcast(Protocol.build("INFO", nick + " joined the studio."));
            broadcastUsers();
            log(nick + " joined (" + clients.size() + " online)");
            return null;
        }
    }

    void disconnect(ClientHandler c) {
        synchronized (lock) {
            if (clients.remove(c) && c.nick() != null) {
                broadcast(Protocol.build("INFO", c.nick() + " left the studio."));
                broadcastUsers();
                log(c.nick() + " left (" + clients.size() + " online)");
            }
        }
    }

    private String validateNick(String nick, ClientHandler self) {
        if (nick == null || nick.isBlank()) return "Nickname cannot be blank.";
        if (!NICK.matcher(nick).matches()) {
            return "Nickname must be 1-16 characters: letters, digits, _ . or -";
        }
        for (ClientHandler other : clients) {
            if (other != self && nick.equalsIgnoreCase(other.nick())) {
                return "Nickname '" + nick + "' is already in use.";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ dispatch
    /** @return false when this client should be disconnected. */
    boolean handle(ClientHandler c, String line) {
        String[] p = Protocol.parse(line);
        synchronized (lock) {
            switch (p[0]) {
                case "MSG":      return onMessage(c, Protocol.arg(p, 1));
                case "IDEA":     onIdea(c, p); break;
                case "FBREQ":    onFeedbackRequest(c, p); break;
                case "FBREPLY":  onFeedbackReply(c, p); break;
                case "REVSUBMIT": onRevisionSubmit(c, p); break;
                case "REVVOTE":  onRevisionVote(c, p); break;
                case "IDEAUPD":  onIdeaUpdate(c, p); break;
                case "IDEADEL":  onIdeaDelete(c, p); break;
                case "FBREQUPD": onRequestUpdate(c, p); break;
                case "FBREQDEL": onRequestDelete(c, p); break;
                case "FBREPLYUPD": onReplyUpdate(c, p); break;
                case "FBREPLYDEL": onReplyDelete(c, p); break;
                case "REVUPD":   onRevisionUpdate(c, p); break;
                case "REVDEL":   onRevisionDelete(c, p); break;
                default:         error(c, "Unknown message type.");
            }
            return true;
        }
    }

    private boolean onMessage(ClientHandler c, String text) {
        if (text.isEmpty()) { error(c, "Blank messages are not sent."); return true; }
        if (text.length() > 500) { error(c, "Message too long (max 500 characters)."); return true; }
        if (text.startsWith("/")) return onCommand(c, text);
        broadcast(Protocol.build("CHAT", now(), c.nick(), text));
        return true;
    }

    private boolean onCommand(ClientHandler c, String text) {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case "/exit":
                c.send("BYE");
                return false;
            case "/help":
                info(c, "Commands: /name <newname> - change nickname | /ideas - list idea cards | /help | /exit");
                info(c, "Use the Idea Board tab to pitch ideas and ask for feedback, and the Revisions tab to approve drafts.");
                return true;
            case "/ideas":
                boolean any = false;
                for (StudioState.Idea i : state.allIdeas()) {
                    any = true;
                    info(c, "#" + i.id + " [" + i.category + "] " + i.title + " by " + i.author
                            + " - " + state.ideaStatus(i));
                }
                if (!any) info(c, "No idea cards yet. Pitch the first one on the Idea Board tab!");
                return true;
            case "/name":
                rename(c, arg);
                return true;
            default:
                error(c, "Unknown command '" + cmd + "'. Type /help.");
                return true;
        }
    }

    private void rename(ClientHandler c, String newNick) {
        String err = validateNick(newNick, c);
        if (err != null) { error(c, err); return; }
        String old = c.nick();
        if (old.equals(newNick)) { error(c, "That is already your nickname."); return; }
        c.setNick(newNick);
        state.renameUser(old, newNick);
        c.send(Protocol.build("NICK", newNick));
        broadcast(Protocol.build("INFO", old + " is now known as " + newNick + "."));
        broadcastUsers();
        resyncAll();   // authors on cards/revisions changed
    }

    // ------------------------------------------------------------------ original feature 1
    private void onIdea(ClientHandler c, String[] p) {
        String category = Protocol.arg(p, 1), title = Protocol.arg(p, 2), summary = Protocol.arg(p, 3);
        if (title.isEmpty()) { error(c, "An idea card needs a title."); return; }
        if (title.length() > 80 || summary.length() > 400) { error(c, "Title max 80, pitch max 400 characters."); return; }
        if (category.isEmpty()) category = "Other";
        StudioState.Idea i = state.addIdea(c.nick(), category, title, summary);
        broadcast(state.ideaLine(i));
        broadcast(Protocol.build("INFO", c.nick() + " pinned a new idea: \"" + title + "\" (" + category + ")"));
    }

    private void onFeedbackRequest(ClientHandler c, String[] p) {
        StudioState.Idea idea = state.idea(parseId(Protocol.arg(p, 1)));
        String question = Protocol.arg(p, 2);
        if (idea == null) { error(c, "That idea card no longer exists."); return; }
        if (!idea.author.equals(c.nick())) { error(c, "Only the idea's author can request feedback on it."); return; }
        if (question.isEmpty()) { error(c, "Say what kind of feedback you want."); return; }
        if (question.length() > 400) { error(c, "Feedback request too long (max 400)."); return; }
        StudioState.FbReq r = state.addReq(idea.id, c.nick(), question);
        broadcast(state.reqLine(r));
        broadcast(Protocol.build("INFO", c.nick() + " wants feedback on \"" + idea.title + "\": " + question));
    }

    private void onFeedbackReply(ClientHandler c, String[] p) {
        StudioState.FbReq req = state.req(parseId(Protocol.arg(p, 1)));
        String comment = Protocol.arg(p, 2);
        if (req == null) { error(c, "That feedback request no longer exists."); return; }
        if (comment.isEmpty()) { error(c, "Blank feedback is not sent."); return; }
        if (comment.length() > 500) { error(c, "Feedback too long (max 500)."); return; }
        broadcast(state.replyLine(state.addReply(req.id, c.nick(), comment, now())));
    }

    // ------------------------------------------------------------------ original feature 2
    private void onRevisionSubmit(ClientHandler c, String[] p) {
        StudioState.Idea idea = state.idea(parseId(Protocol.arg(p, 1)));
        String note = Protocol.arg(p, 2);
        if (idea == null) { error(c, "That idea card no longer exists."); return; }
        if (!idea.author.equals(c.nick())) { error(c, "Only the idea's author can submit revisions."); return; }
        if (note.isEmpty()) { error(c, "Add a revision note so reviewers know what changed."); return; }
        if (note.length() > 400) { error(c, "Revision note too long (max 400)."); return; }
        StudioState.Revision r = state.addRevision(idea.id, c.nick(), note);
        broadcast(state.revLine(r));
        broadcast(state.ideaLine(idea));
        broadcast(Protocol.build("INFO", c.nick() + " submitted version " + r.version + " of \""
                + idea.title + "\" for review (" + StudioState.REQUIRED_APPROVALS + " approvals needed)."));
    }

    private void onRevisionVote(ClientHandler c, String[] p) {
        StudioState.Revision r = state.revision(parseId(Protocol.arg(p, 1)));
        String choice = Protocol.arg(p, 2).toUpperCase();
        String comment = Protocol.arg(p, 3);
        if (r == null) { error(c, "That revision no longer exists."); return; }
        if (r.author.equals(c.nick())) { error(c, "You cannot vote on your own revision."); return; }
        if (!choice.equals(StudioState.APPROVE) && !choice.equals(StudioState.CHANGES)) {
            error(c, "Vote must be APPROVE or CHANGES."); return;
        }
        if (choice.equals(StudioState.CHANGES) && comment.isEmpty()) {
            error(c, "Say what should change so the writer can act on it."); return;
        }
        if (comment.length() > 300) { error(c, "Vote comment too long (max 300)."); return; }
        String before = state.status(r);
        r.votes.put(c.nick(), new StudioState.Vote(choice, comment));
        String after = state.status(r);
        StudioState.Idea idea = state.idea(r.ideaId);
        broadcast(state.revLine(r));
        broadcast(state.ideaLine(idea));
        broadcast(Protocol.build("INFO", c.nick() + (choice.equals(StudioState.APPROVE)
                ? " approved" : " requested changes to") + " v" + r.version + " of \"" + idea.title + "\"."));
        if (!before.equals(after)) {
            broadcast(Protocol.build("INFO", "\"" + idea.title + "\" v" + r.version + " is now: " + after.toUpperCase()));
        }
    }

    // ------------------------------------------------------------------ update / delete
    private void onIdeaUpdate(ClientHandler c, String[] p) {
        StudioState.Idea idea = state.idea(parseId(Protocol.arg(p, 1)));
        String category = Protocol.arg(p, 2), title = Protocol.arg(p, 3), summary = Protocol.arg(p, 4);
        if (idea == null) { error(c, "That idea card no longer exists."); return; }
        if (title.isEmpty()) { error(c, "An idea card needs a title."); return; }
        if (title.length() > 80 || summary.length() > 400) { error(c, "Title max 80, pitch max 400 characters."); return; }
        if (category.isEmpty()) category = "Other";
        state.updateIdea(idea, category, title, summary);
        broadcast(state.ideaLine(idea));
        resyncAll();   // revision rows show the idea title
        broadcast(Protocol.build("INFO", c.nick() + " updated the idea card \"" + title + "\"."));
    }

    private void onIdeaDelete(ClientHandler c, String[] p) {
        StudioState.Idea idea = state.idea(parseId(Protocol.arg(p, 1)));
        if (idea == null) { error(c, "That idea card no longer exists."); return; }
        state.deleteIdea(idea.id);
        resyncAll();
        broadcast(Protocol.build("INFO", c.nick() + " deleted the idea \"" + idea.title
                + "\" along with its feedback and revisions."));
    }

    private void onRequestUpdate(ClientHandler c, String[] p) {
        StudioState.FbReq r = state.req(parseId(Protocol.arg(p, 1)));
        String question = Protocol.arg(p, 2);
        if (r == null) { error(c, "That feedback request no longer exists."); return; }
        if (question.isEmpty()) { error(c, "Say what kind of feedback you want."); return; }
        if (question.length() > 400) { error(c, "Feedback request too long (max 400)."); return; }
        r.question = question;
        broadcast(state.reqLine(r));
        broadcast(Protocol.build("INFO", c.nick() + " edited a feedback request."));
    }

    private void onRequestDelete(ClientHandler c, String[] p) {
        StudioState.FbReq r = state.req(parseId(Protocol.arg(p, 1)));
        if (r == null) { error(c, "That feedback request no longer exists."); return; }
        state.deleteReq(r.id);
        resyncAll();
        broadcast(Protocol.build("INFO", c.nick() + " deleted a feedback request and its replies."));
    }

    private void onReplyUpdate(ClientHandler c, String[] p) {
        StudioState.FbReply r = state.reply(parseId(Protocol.arg(p, 1)));
        String comment = Protocol.arg(p, 2);
        if (r == null) { error(c, "That reply no longer exists."); return; }
        if (comment.isEmpty()) { error(c, "Blank feedback is not sent."); return; }
        if (comment.length() > 500) { error(c, "Feedback too long (max 500)."); return; }
        r.comment = comment;
        broadcast(state.replyLine(r));
        if (!r.author.equals(c.nick()))
            broadcast(Protocol.build("INFO", c.nick() + " edited " + r.author + "'s feedback."));
    }

    private void onReplyDelete(ClientHandler c, String[] p) {
        StudioState.FbReply r = state.reply(parseId(Protocol.arg(p, 1)));
        if (r == null) { error(c, "That reply no longer exists."); return; }
        state.deleteReply(r.id);
        resyncAll();
        broadcast(Protocol.build("INFO", c.nick() + " deleted "
                + (r.author.equals(c.nick()) ? "their" : r.author + "'s") + " feedback reply."));
    }

    private void onRevisionUpdate(ClientHandler c, String[] p) {
        StudioState.Revision r = state.revision(parseId(Protocol.arg(p, 1)));
        String note = Protocol.arg(p, 2);
        if (r == null) { error(c, "That revision no longer exists."); return; }
        if (!r.author.equals(c.nick())) { error(c, "Only the revision's author can edit it."); return; }
        if (note.isEmpty()) { error(c, "Add a revision note so reviewers know what changed."); return; }
        if (note.length() > 400) { error(c, "Revision note too long (max 400)."); return; }
        r.note = note;
        r.votes.clear();   // reviewers voted on the old text, so the review starts over
        StudioState.Idea idea = state.idea(r.ideaId);
        broadcast(state.revLine(r));
        if (idea != null) broadcast(state.ideaLine(idea));
        broadcast(Protocol.build("INFO", c.nick() + " edited v" + r.version + " of \""
                + (idea == null ? "?" : idea.title) + "\"; earlier votes were cleared."));
    }

    private void onRevisionDelete(ClientHandler c, String[] p) {
        StudioState.Revision r = state.revision(parseId(Protocol.arg(p, 1)));
        if (r == null) { error(c, "That revision no longer exists."); return; }
        if (!r.author.equals(c.nick())) { error(c, "Only the revision's author can delete it."); return; }
        StudioState.Idea idea = state.idea(r.ideaId);
        state.deleteRevision(r.id);
        resyncAll();
        broadcast(Protocol.build("INFO", c.nick() + " deleted v" + r.version + " of \""
                + (idea == null ? "?" : idea.title) + "\"."));
    }

    // ------------------------------------------------------------------ server console (admin)
    /** Reads commands typed into the server window. The operator may edit/delete anything. */
    private void startConsole() {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    synchronized (lock) { adminCommand(line); }
                }
            } catch (IOException ignored) { }
        }, "server-console");
        t.setDaemon(true);
        t.start();
        log("Server console ready. Type 'help' for admin commands.");
    }

    private void adminCommand(String line) {
        String[] w = line.split("\\s+", 4);
        String cmd = w[0].toLowerCase();
        String kind = w.length > 1 ? w[1].toLowerCase() : "";
        int id = w.length > 2 ? parseId(w[2]) : -1;
        String text = w.length > 3 ? w[3].trim() : "";
        switch (cmd) {
            case "help":
                log("Admin commands (kinds: idea | request | reply | revision):");
                log("  list                          - show everything with its id");
                log("  delete <kind> <id>            - remove an item (idea also removes its feedback and revisions)");
                log("  edit idea <id> <category>|<title>|<pitch>");
                log("  edit request <id> <new text>  - same for reply and revision");
                return;
            case "list":
                for (StudioState.Idea i : state.allIdeas())
                    log("idea #" + i.id + " [" + i.category + "] " + i.title + " by " + i.author + " - " + state.ideaStatus(i));
                for (StudioState.FbReq r : state.allReqs())
                    log("request #" + r.id + " (idea #" + r.ideaId + ") by " + r.author + ": " + r.question);
                for (StudioState.FbReply r : state.allReplies())
                    log("reply #" + r.id + " (request #" + r.reqId + ") by " + r.author + ": " + r.comment);
                for (StudioState.Revision r : state.allRevisions())
                    log("revision #" + r.id + " (idea #" + r.ideaId + " v" + r.version + ") by " + r.author
                            + " - " + state.status(r) + ": " + r.note);
                return;
            case "delete": adminDelete(kind, id); return;
            case "edit":   adminEdit(kind, id, text); return;
            default:       log("Unknown command. Type 'help'.");
        }
    }

    private void adminDelete(String kind, int id) {
        String what;
        switch (kind) {
            case "idea": {
                StudioState.Idea i = state.idea(id);
                if (i == null) { log("No idea #" + id); return; }
                state.deleteIdea(id);
                what = "the idea \"" + i.title + "\" and its feedback and revisions";
                break;
            }
            case "request":
                if (state.req(id) == null) { log("No request #" + id); return; }
                state.deleteReq(id);
                what = "a feedback request and its replies";
                break;
            case "reply":
                if (state.reply(id) == null) { log("No reply #" + id); return; }
                state.deleteReply(id);
                what = "a reply";
                break;
            case "revision":
                if (state.revision(id) == null) { log("No revision #" + id); return; }
                state.deleteRevision(id);
                what = "a revision";
                break;
            default:
                log("Usage: delete <idea|request|reply|revision> <id>");
                return;
        }
        resyncAll();
        broadcast(Protocol.build("INFO", "The server operator deleted " + what + "."));
        log("Deleted " + kind + " #" + id);
    }

    private void adminEdit(String kind, int id, String text) {
        if (text.isEmpty()) { log("Usage: edit <kind> <id> <new text>  (see 'help')"); return; }
        switch (kind) {
            case "idea": {
                StudioState.Idea i = state.idea(id);
                if (i == null) { log("No idea #" + id); return; }
                String[] f = text.split("\\|", 3);
                String category = f[0].trim(), title = f.length > 1 ? f[1].trim() : "", summary = f.length > 2 ? f[2].trim() : "";
                if (title.isEmpty()) { log("Usage: edit idea <id> <category>|<title>|<pitch>"); return; }
                if (title.length() > 80 || summary.length() > 400) { log("Title max 80, pitch max 400 characters."); return; }
                state.updateIdea(i, category.isEmpty() ? "Other" : category, title, summary);
                break;
            }
            case "request": {
                StudioState.FbReq r = state.req(id);
                if (r == null) { log("No request #" + id); return; }
                if (text.length() > 400) { log("Max 400 characters."); return; }
                r.question = text;
                break;
            }
            case "reply": {
                StudioState.FbReply r = state.reply(id);
                if (r == null) { log("No reply #" + id); return; }
                if (text.length() > 500) { log("Max 500 characters."); return; }
                r.comment = text;
                break;
            }
            case "revision": {
                StudioState.Revision r = state.revision(id);
                if (r == null) { log("No revision #" + id); return; }
                if (text.length() > 400) { log("Max 400 characters."); return; }
                r.note = text;
                r.votes.clear();   // reviewers voted on the old text
                break;
            }
            default:
                log("Usage: edit <idea|request|reply|revision> <id> <new text>");
                return;
        }
        resyncAll();
        broadcast(Protocol.build("INFO", "The server operator edited a " + kind + "."));
        log("Edited " + kind + " #" + id);
    }

    // ------------------------------------------------------------------ helpers (call under lock)
    private void broadcast(String line) {
        for (ClientHandler c : new ArrayList<>(clients)) c.send(line);
    }

    private void broadcastUsers() {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler c : clients) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.nick());
        }
        broadcast(Protocol.build("USERS", sb.toString()));
    }

    private void resyncAll() {
        broadcast("CLEAR");
        for (String l : state.snapshot()) broadcast(l);
    }

    private void error(ClientHandler c, String msg) { c.send(Protocol.build("ERROR", msg)); }
    private void info(ClientHandler c, String msg) { c.send(Protocol.build("INFO", msg)); }
    private static String now() { return LocalTime.now().format(TIME); }

    private static int parseId(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }
}

// ===== ClientHandler =====
/** One thread per connected client: reads lines and hands them to the server. */
final class ClientHandler implements Runnable {
    private final Socket socket;
    private final StudioServer server;
    private PrintWriter out;
    private volatile String nick;

    ClientHandler(Socket socket, StudioServer server) {
        this.socket = socket;
        this.server = server;
    }

    String nick() { return nick; }
    void setNick(String nick) { this.nick = nick; }
    String address() { return String.valueOf(socket.getRemoteSocketAddress()); }

    /** Synchronized so two threads broadcasting at once can never interleave one line. */
    synchronized void send(String line) {
        if (out != null) out.println(line);
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            String line;
            // Phase 1: wait for a valid JOIN
            while (nick == null && (line = in.readLine()) != null) {
                String[] p = Protocol.parse(line);
                if (!p[0].equals("JOIN")) {
                    send(Protocol.build("ERROR", "Send JOIN with a nickname first."));
                    continue;
                }
                String err = server.register(this, Protocol.arg(p, 1));
                if (err != null) send(Protocol.build("ERROR", err));
            }
            // Phase 2: normal traffic
            while (nick != null && (line = in.readLine()) != null) {
                if (!server.handle(this, line)) break;
            }
        } catch (IOException e) {
            server.log("Connection problem with " + address() + ": " + e.getMessage());
        } finally {
            server.disconnect(this);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}

// ===== StudioState =====
/**
 * Shared writing-studio data held in server memory (idea cards, feedback requests,
 * feedback replies, revisions and votes). Only touched while holding the server lock.
 */
final class StudioState {
    static final int REQUIRED_APPROVALS = 2;
    static final String APPROVE = "APPROVE";
    static final String CHANGES = "CHANGES";

    static final class Idea {
        final int id; String author; String category, title, summary;
        Idea(int id, String author, String category, String title, String summary) {
            this.id = id; this.author = author; this.category = category;
            this.title = title; this.summary = summary;
        }
    }

    static final class FbReq {
        final int id, ideaId; String author; String question;
        FbReq(int id, int ideaId, String author, String question) {
            this.id = id; this.ideaId = ideaId; this.author = author; this.question = question;
        }
    }

    static final class FbReply {
        final int id, reqId; String author; String comment; final String time;
        FbReply(int id, int reqId, String author, String comment, String time) {
            this.id = id; this.reqId = reqId; this.author = author;
            this.comment = comment; this.time = time;
        }
    }

    static final class Vote {
        final String choice, comment;
        Vote(String choice, String comment) { this.choice = choice; this.comment = comment; }
    }

    static final class Revision {
        final int id, ideaId, version; String author; String note;
        final Map<String, Vote> votes = new LinkedHashMap<>();
        Revision(int id, int ideaId, int version, String author, String note) {
            this.id = id; this.ideaId = ideaId; this.version = version;
            this.author = author; this.note = note;
        }
    }

    private final Map<Integer, Idea> ideas = new LinkedHashMap<>();
    private final List<FbReq> reqs = new ArrayList<>();
    private final List<FbReply> replies = new ArrayList<>();
    private final List<Revision> revisions = new ArrayList<>();
    private int nextIdea = 1, nextReq = 1, nextReply = 1, nextRev = 1;

    // ---- mutations -------------------------------------------------------
    Idea addIdea(String author, String category, String title, String summary) {
        Idea i = new Idea(nextIdea++, author, category, title, summary);
        ideas.put(i.id, i);
        return i;
    }

    FbReq addReq(int ideaId, String author, String question) {
        FbReq r = new FbReq(nextReq++, ideaId, author, question);
        reqs.add(r);
        return r;
    }

    FbReply addReply(int reqId, String author, String comment, String time) {
        FbReply r = new FbReply(nextReply++, reqId, author, comment, time);
        replies.add(r);
        return r;
    }

    Revision addRevision(int ideaId, String author, String note) {
        int version = 0;   // newest + 1, so numbers stay unique after a deletion
        for (Revision r : revisions) if (r.ideaId == ideaId) version = Math.max(version, r.version);
        version++;
        Revision r = new Revision(nextRev++, ideaId, version, author, note);
        revisions.add(r);
        return r;
    }

    void updateIdea(Idea i, String category, String title, String summary) {
        i.category = category; i.title = title; i.summary = summary;
    }

    /** Removes the idea and everything hanging off it (feedback requests, replies, revisions). */
    void deleteIdea(int ideaId) {
        ideas.remove(ideaId);
        List<Integer> reqIds = new ArrayList<>();
        for (FbReq r : reqs) if (r.ideaId == ideaId) reqIds.add(r.id);
        for (int id : reqIds) deleteReq(id);
        revisions.removeIf(r -> r.ideaId == ideaId);
    }

    void deleteReq(int reqId) {
        reqs.removeIf(r -> r.id == reqId);
        replies.removeIf(r -> r.reqId == reqId);
    }

    void deleteReply(int replyId) { replies.removeIf(r -> r.id == replyId); }

    void deleteRevision(int revId) { revisions.removeIf(r -> r.id == revId); }

    void renameUser(String oldNick, String newNick) {
        for (Idea i : ideas.values()) if (i.author.equals(oldNick)) i.author = newNick;
        for (FbReq r : reqs) if (r.author.equals(oldNick)) r.author = newNick;
        for (FbReply r : replies) if (r.author.equals(oldNick)) r.author = newNick;
        for (Revision r : revisions) {
            if (r.author.equals(oldNick)) r.author = newNick;
            Map<String, Vote> renamed = new LinkedHashMap<>();
            for (Map.Entry<String, Vote> e : r.votes.entrySet()) {
                renamed.put(e.getKey().equals(oldNick) ? newNick : e.getKey(), e.getValue());
            }
            r.votes.clear();
            r.votes.putAll(renamed);
        }
    }

    // ---- lookups ---------------------------------------------------------
    Idea idea(int id) { return ideas.get(id); }
    Iterable<Idea> allIdeas() { return ideas.values(); }
    Iterable<FbReq> allReqs() { return reqs; }
    Iterable<FbReply> allReplies() { return replies; }
    Iterable<Revision> allRevisions() { return revisions; }

    FbReq req(int id) {
        for (FbReq r : reqs) if (r.id == id) return r;
        return null;
    }

    FbReply reply(int id) {
        for (FbReply r : replies) if (r.id == id) return r;
        return null;
    }

    Revision revision(int id) {
        for (Revision r : revisions) if (r.id == id) return r;
        return null;
    }

    // ---- revision approval workflow -------------------------------------
    int count(Revision r, String choice) {
        int n = 0;
        for (Vote v : r.votes.values()) if (v.choice.equals(choice)) n++;
        return n;
    }

    /** Any "changes" vote blocks approval; otherwise enough approvals approve it. */
    String status(Revision r) {
        if (count(r, CHANGES) > 0) return "Needs Changes";
        if (count(r, APPROVE) >= REQUIRED_APPROVALS) return "Approved";
        return "Pending";
    }

    /** Status shown on an idea card = status of its newest revision. */
    String ideaStatus(Idea idea) {
        Revision latest = null;
        for (Revision r : revisions) if (r.ideaId == idea.id) latest = r;
        if (latest == null) return "Draft";
        String s = status(latest);
        return s.equals("Pending") ? "In Review" : s;
    }

    // ---- wire lines ------------------------------------------------------
    private static String s(int n) { return String.valueOf(n); }

    String ideaLine(Idea i) {
        return Protocol.build("IDEA", s(i.id), i.author, i.category, i.title, i.summary, ideaStatus(i));
    }

    String reqLine(FbReq r) {
        return Protocol.build("FBREQ", s(r.id), s(r.ideaId), r.author, r.question);
    }

    String replyLine(FbReply r) {
        return Protocol.build("FBREPLY", s(r.id), s(r.reqId), r.author, r.comment, r.time);
    }

    String revLine(Revision r) {
        Idea idea = ideas.get(r.ideaId);
        StringBuilder votes = new StringBuilder();
        for (Map.Entry<String, Vote> e : r.votes.entrySet()) {
            if (votes.length() > 0) votes.append('\n');
            votes.append(e.getKey()).append(": ");
            votes.append(e.getValue().choice.equals(APPROVE) ? "approved" : "requested changes");
            if (!e.getValue().comment.isEmpty()) votes.append(" - ").append(e.getValue().comment);
        }
        return Protocol.build("REV", s(r.id), s(r.ideaId), idea == null ? "?" : idea.title,
                s(r.version), r.author, r.note, status(r),
                s(count(r, APPROVE)), s(count(r, CHANGES)), votes.toString());
    }

    /** Everything a newly joined (or resynchronising) client needs, in dependency order. */
    List<String> snapshot() {
        List<String> out = new ArrayList<>();
        for (Idea i : ideas.values()) out.add(ideaLine(i));
        for (FbReq r : reqs) out.add(reqLine(r));
        for (FbReply r : replies) out.add(replyLine(r));
        for (Revision r : revisions) out.add(revLine(r));
        return out;
    }
}

// ===== Protocol =====
/**
 * Sherva wire format: one message per line.
 *   TYPE|field1|field2|...
 * Every field is URL-encoded, so '|' and line breaks inside user text can never
 * break the framing. Both server and client use only these helpers.
 */
final class Protocol {
    public static final int DEFAULT_PORT = 5050;

    private Protocol() { }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public static String build(String type, String... fields) {
        StringBuilder sb = new StringBuilder(type);
        for (String f : fields) {
            sb.append('|').append(enc(f == null ? "" : f));
        }
        return sb.toString();
    }

    /** Returns [TYPE, decoded fields...]. Malformed fields become empty strings. */
    public static String[] parse(String line) {
        String[] parts = line.split("\\|", -1);
        for (int i = 1; i < parts.length; i++) {
            try {
                parts[i] = dec(parts[i]);
            } catch (IllegalArgumentException e) {
                parts[i] = "";
            }
        }
        return parts;
    }

    /** Safe, trimmed access to field i (empty string when missing). */
    public static String arg(String[] p, int i) {
        return i < p.length ? p[i].trim() : "";
    }
}
