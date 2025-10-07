package payment.datareplication;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;


public class Ledger {
    private final File ledgerFile;
    private final File idsFile;
    private final File walFile;
    private final File commitFile;
    
    private final Map<String, String> entries = new LinkedHashMap<>();
    private final java.util.List<String> walIndex = new java.util.ArrayList<>();

    public Ledger(String dataDir) throws IOException {
        File dir = new File(dataDir);
        if (!dir.exists()) dir.mkdirs();
        ledgerFile = new File(dir, "ledger.txt");
        idsFile = new File(dir, "ids.txt");
        walFile = new File(dir, "wal.txt");
        commitFile = new File(dir, "commit.idx");
        load();
    }

    private synchronized void load() throws IOException {
        if (idsFile.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(idsFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    entries.put(line, "RESTORED");
                }
            }
        }
        // load WAL into walIndex
        if (walFile.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(walFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    walIndex.add(line);
                }
            }
        }
        
        if (commitFile.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(commitFile), StandardCharsets.UTF_8))) {
                String l = br.readLine();
                if (l != null) {
                    try { commitIndex = Integer.parseInt(l.trim()); } catch (Exception ignored) { commitIndex = -1; }
                }
            }
        }
    }

    
    private int commitIndex = -1;

    public synchronized int getCommitIndex() { return commitIndex; }

    
    public synchronized void advanceCommitIndex(int newSeq) throws IOException {
        if (newSeq <= commitIndex) return;
        try (FileOutputStream fos = new FileOutputStream(commitFile, false);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            ow.write(String.valueOf(newSeq));
            ow.flush();
            fos.getFD().sync();
        }
        commitIndex = newSeq;
        
        maybeSnapshot();
    }

    private void maybeSnapshot() throws IOException {
        
        int SNAPSHOT_THRESHOLD = 100;
        if (walIndex.size() <= SNAPSHOT_THRESHOLD) return;
        File snapshot = new File(ledgerFile.getParentFile(), "ledger.snapshot");
        
        try (FileOutputStream fos = new FileOutputStream(snapshot, false);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            for (int i = 0; i <= commitIndex && i < walIndex.size(); i++) {
                ow.write(walIndex.get(i) + "\n");
            }
            ow.flush();
            fos.getFD().sync();
        }
        
        File newWal = new File(walFile.getAbsolutePath() + ".new");
        try (FileOutputStream fos = new FileOutputStream(newWal, false);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            for (int i = commitIndex + 1; i < walIndex.size(); i++) {
                ow.write(walIndex.get(i) + "\n");
            }
            ow.flush();
            fos.getFD().sync();
        }
        if (!newWal.renameTo(walFile)) {
            throw new IOException("Failed to rotate WAL");
        }
        
        java.util.List<String> newIndex = new java.util.ArrayList<>();
        for (int i = commitIndex + 1; i < walIndex.size(); i++) newIndex.add(walIndex.get(i));
        walIndex.clear();
        walIndex.addAll(newIndex);
    }

    
    public synchronized int appendLeader(String id, String payload) throws IOException {
        if (entries.containsKey(id)) return -1; 
        int seq = walIndex.size();
        String line = seq + " " + id + " " + payload;
        
        try (FileOutputStream fos = new FileOutputStream(walFile, true);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            ow.write(line + "\n");
            ow.flush();
            fos.getFD().sync();
        }
        walIndex.add(line);
       
        try (FileOutputStream fos = new FileOutputStream(ledgerFile, true);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            ow.write(line + "\n");
            ow.flush();
            fos.getFD().sync();
        }
        
        try (FileOutputStream fos = new FileOutputStream(idsFile, true);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            ow.write(id + "\n");
            ow.flush();
            fos.getFD().sync();
        }
        entries.put(id, payload);
        return seq;
    }

    
    public synchronized boolean appendFollower(int seq, String id, String payload) throws IOException {
        
        if (entries.containsKey(id)) return false;
        String line = seq + " " + id + " " + payload;
        if (seq < 0) return false;
        if (seq < walIndex.size()) {
            
            String existing = walIndex.get(seq);
            if (existing.equals(line)) {
                
                entries.put(id, payload);
                try (FileOutputStream fos = new FileOutputStream(idsFile, true);
                     OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    ow.write(id + "\n");
                    ow.flush();
                    fos.getFD().sync();
                }
                return false;
            } else {
                
                throw new IOException("WAL conflict at seq " + seq + ": existing=" + existing + " new=" + line);
            }
        }
        if (seq != walIndex.size()) {
            
            return false;
        }
        
        try (FileOutputStream fos = new FileOutputStream(walFile, true);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            ow.write(line + "\n");
            ow.flush();
            fos.getFD().sync();
        }
        walIndex.add(line);
        try (FileOutputStream fos = new FileOutputStream(ledgerFile, true);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            ow.write(line + "\n");
            ow.flush();
            fos.getFD().sync();
        }
        try (FileOutputStream fos = new FileOutputStream(idsFile, true);
             OutputStreamWriter ow = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            ow.write(id + "\n");
            ow.flush();
            fos.getFD().sync();
        }
        entries.put(id, payload);
        return true;
    }

   
    public synchronized String[] getWalFrom(int fromIndex) {
        if (fromIndex < 0) fromIndex = 0;
        if (fromIndex >= walIndex.size()) return new String[0];
        java.util.List<String> sub = walIndex.subList(fromIndex, walIndex.size());
        return sub.toArray(new String[0]);
    }

    
    public synchronized void compactIds() throws IOException {
        
        File tmp = new File(idsFile.getAbsolutePath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp, false), StandardCharsets.UTF_8)) {
            for (String id : entries.keySet()) {
                w.write(id + "\n");
            }
        }
        if (!tmp.renameTo(idsFile)) {
            throw new IOException("Failed to rename compacted ids file");
        }
    }

    public synchronized boolean seen(String id) {
        return entries.containsKey(id);
    }
}
