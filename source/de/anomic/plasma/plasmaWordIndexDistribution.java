

package de.anomic.plasma;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Vector;
import java.util.Iterator;

import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacySeedDB;
import de.anomic.yacy.yacyClient;
import de.anomic.server.serverCodings;
import de.anomic.server.logging.serverLog;
import de.anomic.kelondro.kelondroException;

public class plasmaWordIndexDistribution {
    
    // distributes parts of the index to other peers
    // stops as soon as an error occurrs
    
    private int indexCount;
    private int juniorPeerCount, seniorPeerCount;
    private long maxTime;
    
    private plasmaURLPool urlPool;
    private plasmaWordIndex wordIndex;
    private serverLog log;
    private boolean enabled;
    
    public plasmaWordIndexDistribution(plasmaURLPool urlPool, plasmaWordIndex wordIndex, serverLog log,
    boolean enable) {
        this.urlPool = urlPool;
        this.wordIndex = wordIndex;
        this.enabled = enable;
        this.log = log;
        setCounts(100 /*indexCount*/,  1 /*juniorPeerCount*/, 3 /*seniorPeerCount*/, 8000);
    }
    
    public void enable() {
        enabled = true;
    }
    
    public void disable() {
        enabled = false;
    }
    
    public boolean job() {

        if (yacyCore.seedDB == null) {
            log.logDebug("no word distribution: seedDB == null");
            return false;
        }
        if (yacyCore.seedDB.mySeed == null) {
            log.logDebug("no word distribution: mySeed == null");
            return false;
        }
        if (yacyCore.seedDB.mySeed.isVirgin()) {
            log.logDebug("no word distribution: status is virgin");
            return false;
        }
        if (!(enabled)) {
            log.logDebug("no word distribution: not enabled");
            return false;
        }
        if (urlPool.loadedURL.size() < 10) {
            log.logDebug("no word distribution: loadedURL.size() = " + urlPool.loadedURL.size());
            return false;
        }
        if (wordIndex.size() < 100) {
            log.logDebug("no word distribution: not enough words - wordIndex.size() = " + wordIndex.size());
            return false;
        }
        if (urlPool.noticeURL.stackSize() > 0) {
            log.logDebug("no word distribution: crawl in progress - noticeURL.stackSize() = " + urlPool.noticeURL.stackSize());
            return false;
        }
        
        // do the transfer
        int peerCount = (yacyCore.seedDB.mySeed.isJunior()) ? juniorPeerCount : seniorPeerCount;
        long starttime = System.currentTimeMillis();
        int transferred = performTransferIndex(indexCount, peerCount, true);
        
        if (transferred <= 0) {
            log.logDebug("no word distribution: transfer failed");
            return false;
        }

        // adopt transfer count
        if ((System.currentTimeMillis() - starttime) > (maxTime * peerCount))
            indexCount--;
        else
            indexCount++;
        if (indexCount < 30) indexCount = 30;

        // show success
        return true;
        
    }
    
    public void setCounts(int indexCount, int juniorPeerCount, int seniorPeerCount, long maxTimePerTransfer) {
        this.maxTime = maxTimePerTransfer;
        this.indexCount = indexCount;
        if (indexCount < 30) indexCount = 30;
        this.juniorPeerCount = juniorPeerCount;
        this.seniorPeerCount = seniorPeerCount;
    }
    
    public int performTransferIndex(int indexCount, int peerCount, boolean delete) {
        if ((yacyCore.seedDB == null) || (yacyCore.seedDB.sizeConnected() == 0)) return -1;
        
        // collect index
        //String startPointHash = yacyCore.seedCache.mySeed.hash;
        String startPointHash = serverCodings.encodeMD5B64("" + System.currentTimeMillis(), true).substring(0, yacySeedDB.commonHashLength);
        plasmaWordIndexEntity[] indexEntities = selectTransferIndexes(startPointHash, indexCount);
        if ((indexEntities == null) || (indexEntities.length == 0)) {
            log.logDebug("No Index available for Index Transfer, hash start-point " + startPointHash);
            return -1;
        }
        // count the indexes again, can be smaller as expected
        indexCount = 0; for (int i = 0; i < indexEntities.length; i++) indexCount += indexEntities[i].size();
        
        // find start point for DHT-selection
        String keyhash = indexEntities[indexEntities.length - 1].wordHash();
        
        // iterate over DHT-peers and send away the indexes
        yacySeed seed;
        int hc = 0;
        Enumeration e = yacyCore.dhtAgent.getAcceptRemoteIndexSeeds(keyhash);
        String error;
        String peerNames = "";
        while ((e.hasMoreElements()) && (hc < peerCount)) {
            seed = (yacySeed) e.nextElement();
            if (seed != null) {
                error = yacyClient.transferIndex(seed, indexEntities, urlPool.loadedURL);
                if (error == null) {
                    log.logInfo("Index Transfer of " + indexCount + " words [" + indexEntities[0].wordHash() + " .. " + indexEntities[indexEntities.length-1].wordHash() + "] to peer " + seed.getName() + ":" + seed.hash + " successfull");
                    peerNames += ", " + seed.getName();
                    hc++;
                } else {
                    log.logWarning("Index Transfer to peer " + seed.getName() + ":" + seed.hash + " failed:'" + error + "', disconnecting peer");
                    yacyCore.peerActions.peerDeparture(seed);
                }
            }
        }
        if (peerNames.length() > 0) peerNames = peerNames.substring(2); // remove comma
        
        // clean up and finish with deletion of indexes
        if (hc >= peerCount) {
            // success
            if (delete) {
                try {
                    if (deleteTransferIndexes(indexEntities)) {
                        log.logDebug("Deleted all transferred whole-word indexes locally");
                        return indexCount;
                    } else {
                        log.logError("Deleted not all transferred whole-word indexes");
                        return -1;
                    }
                } catch (IOException ee) {
                    log.logError("Deletion of Indexes not possible:" + ee.getMessage());
                    ee.printStackTrace();
                    return -1;
                }
            } else {
                // simply close the indexEntities
                for (int i = 0; i < indexEntities.length; i++) try {
                    indexEntities[i].close();
                } catch (IOException ee) {}
            }
            return indexCount;
        } else {
            log.logError("Index distribution failed. Too less peers (" + hc + ") received the index, not deleted locally.");
            return -1;
        }
    }
    
    private plasmaWordIndexEntity[] selectTransferIndexes(String hash, int count) {
        Vector tmpEntities = new Vector();
        String nexthash = "";
        try {
            Iterator wordHashIterator = wordIndex.wordHashes(hash, true, true);
            plasmaWordIndexEntity indexEntity, tmpEntity;
            Enumeration urlEnum;
            plasmaWordIndexEntry indexEntry;
            while ((count > 0) && (wordHashIterator.hasNext()) &&
            ((nexthash = (String) wordHashIterator.next()) != null) && (nexthash.trim().length() > 0)) {
                indexEntity = wordIndex.getEntity(nexthash, true);
                if (indexEntity.size() == 0) {
                    indexEntity.deleteComplete();
                } else if (indexEntity.size() <= count) {
                    // take the whole entity
                    tmpEntities.add(indexEntity);
                    log.logDebug("Selected Whole Index (" + indexEntity.size() + " urls) for word " + indexEntity.wordHash());
                    count -= indexEntity.size();
                } else {
                    // make an on-the-fly entity and insert values
                    tmpEntity = new plasmaWordIndexEntity(indexEntity.wordHash());
                    urlEnum = indexEntity.elements(true);
                    while ((urlEnum.hasMoreElements()) && (count > 0)) {
                        indexEntry = (plasmaWordIndexEntry) urlEnum.nextElement();
                        tmpEntity.addEntry(indexEntry);
                        count--;
                    }
                    urlEnum = null;
                    log.logDebug("Selected Partial Index (" + tmpEntity.size() + " from " + indexEntity.size() +" urls) for word " + tmpEntity.wordHash());
                    tmpEntities.add(tmpEntity);
                    indexEntity.close(); // important: is not closed elswhere and cannot be deleted afterwards
                    indexEntity = null;
                }
                
            }
            // transfer to array
            plasmaWordIndexEntity[] indexEntities = new plasmaWordIndexEntity[tmpEntities.size()];
            for (int i = 0; i < tmpEntities.size(); i++) indexEntities[i] = (plasmaWordIndexEntity) tmpEntities.elementAt(i);
            return indexEntities;
        } catch (IOException e) {
            log.logError("selectTransferIndexes IO-Error (hash=" + nexthash + "): " + e.getMessage());
            e.printStackTrace();
            return new plasmaWordIndexEntity[0];
        } catch (kelondroException e) {
            log.logError("selectTransferIndexes database corrupted: " + e.getMessage());
            e.printStackTrace();
            return new plasmaWordIndexEntity[0];
        }
    }
    
    private boolean deleteTransferIndexes(plasmaWordIndexEntity[] indexEntities) throws IOException {
        String wordhash;
        Enumeration urlEnum;
        plasmaWordIndexEntry indexEntry;
        plasmaWordIndexEntity indexEntity;
        String[] urlHashes;
        int sz;
        boolean success = true;
        for (int i = 0; i < indexEntities.length; i++) {
            if (indexEntities[i].isTMPEntity()) {
                // delete entries separately
                int c = 0;
                urlHashes = new String[indexEntities[i].size()];
                urlEnum = indexEntities[i].elements(true);
                while (urlEnum.hasMoreElements()) {
                    indexEntry = (plasmaWordIndexEntry) urlEnum.nextElement();
                    urlHashes[c++] = indexEntry.getUrlHash();
                }
                wordIndex.removeEntries(indexEntities[i].wordHash(), urlHashes, true);
                indexEntity = wordIndex.getEntity(indexEntities[i].wordHash(), true);
                sz = indexEntity.size();
                indexEntity.close();
                log.logDebug("Deleted Partinal Index (" + c + " urls) for word " + indexEntities[i].wordHash() + "; " + sz + " entries left");
                // DEBUG: now try to delete the remaining index. If this works, this routine is fine
                /*
                if (wordIndex.getEntity(indexEntities[i].wordHash()).deleteComplete())
                    System.out.println("DEBUG: trial delete of partial word index " + indexEntities[i].wordHash() + " SUCCESSFULL");
                else
                    System.out.println("DEBUG: trial delete of partial word index " + indexEntities[i].wordHash() + " FAILED");
                 */
                // end debug
                indexEntities[i].close();
            } else {
                // delete complete file
                if (indexEntities[i].deleteComplete()) {
                    indexEntities[i].close();
                } else {
                    indexEntities[i].close();
                    // have another try...
                    if (!(plasmaWordIndexEntity.wordHash2path(wordIndex.getRoot() /*PLASMADB*/, indexEntities[i].wordHash()).delete())) {
                        success = false;
                        log.logError("Could not delete whole Index for word " + indexEntities[i].wordHash());
                    }
                }
            }
            indexEntities[i] = null;
        }
        return success;
    }
}
