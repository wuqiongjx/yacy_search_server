// NetworkPicture.java 
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
// Created 08.10.2005
//
// $LastChangedDate: 2005-09-29 02:24:09 +0200 (Thu, 29 Sep 2005) $
// $LastChangedRevision: 811 $
// $LastChangedBy: orbiter $
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.


import java.awt.image.BufferedImage; 
import java.util.Enumeration;
import java.util.Date;

import de.anomic.http.httpHeader;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.tools.ImagePainter;

import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacySeed;

// draw a picture of the yacy network

public class NetworkPicture {
    
    private static int shortestName = 10;
    private static int longestName = 12;
    
    public static BufferedImage respond(httpHeader header, serverObjects post, serverSwitch env) {

        int width = 640;
        int height = 420;
        
        if (post != null) {
            width = post.getInt("width", 640);
            height = post.getInt("height", 420);
        }
        
        int innerradius = Math.min(width, height) / 5;
        int outerradius = innerradius + innerradius * yacyCore.seedDB.sizeConnected() / 100;
        if (outerradius > innerradius * 2) outerradius = innerradius * 2;
        
        shortestName = 10;
        longestName = 12;
    
        ImagePainter img = new ImagePainter(width, height, 0);
        img.setMode(ImagePainter.MODE_ADD);

        if (yacyCore.seedDB == null) return img.toImage(true); // no other peers known
        int size = yacyCore.seedDB.sizeConnected();
        if (size == 0) return img.toImage(true); // no other peers known
        
        // draw network circle
        img.setColor("004020");
        img.arc(width / 2, height / 2, innerradius - 20, innerradius + 20, 0, 360);
        
        //System.out.println("Seed Maximum distance is       " + yacySeed.maxDHTDistance);
        //System.out.println("Seed Minimum distance is       " + yacySeed.minDHTNumber);
        
        final int maxCount = 300;
        yacySeed seed;
        int angle;
        long lastseen;
        
        // draw connected senior and principals
        int count = 0;
        int totalCount = 0;
        Enumeration e = yacyCore.seedDB.seedsConnected(true, false, null);
        while (e.hasMoreElements() && count < maxCount) {
            seed = (yacySeed) e.nextElement();
            if (seed != null) {
                drawPeer(img, width / 2, height / 2, innerradius, outerradius, seed, "000040", "B0FFB0");
                count++;
            }
        }
        totalCount += count;
        
        // draw disconnected senior and principals that have been seen lately
        count = 0;
        e = yacyCore.seedDB.seedsSortedDisconnected(true, yacySeed.STR_LASTSEEN);
        while (e.hasMoreElements() && count < maxCount) {
            seed = (yacySeed) e.nextElement();
            if (seed != null) {
                lastseen = Math.abs((System.currentTimeMillis() - seed.getLastSeenTime()) / 1000 / 60);
                if (lastseen > 120) break; // we have enough, this list is sorted so we don't miss anything
                drawPeer(img, width / 2, height / 2, innerradius, outerradius, seed, "101010", "802000");
                count++;
            }
        }
        totalCount += count;
        
        // draw juniors that have been seen lately
        count = 0;
        e = yacyCore.seedDB.seedsSortedPotential(true, yacySeed.STR_LASTSEEN);
        while (e.hasMoreElements() && count < maxCount) {
            seed = (yacySeed) e.nextElement();
            if (seed != null) {
                lastseen = Math.abs((System.currentTimeMillis() - seed.getLastSeenTime()) / 1000 / 60);
                if (lastseen > 120) break; // we have enough, this list is sorted so we don't miss anything
                drawPeer(img, width / 2, height / 2, innerradius, outerradius, seed, "202000", "A0A000");
                count++;
            }
        }
        totalCount += count;
        
        // draw my own peer
        drawPeer(img, width / 2, height / 2, innerradius, outerradius, yacyCore.seedDB.mySeed, "800000", "FFFFFF");
        
        // draw description
        img.setColor("FFFFFF");
        img.print(2, 8, "THE YACY NETWORK", true);
        img.print(2, 16, "DRAWING OF " + totalCount + " SELECTED PEERS", true);
        img.print(width - 2, 8, "SNAPSHOT FROM " + new Date().toString().toUpperCase(), false);
        
        return img.toImage(true);
    }
    
    private static void drawPeer(ImagePainter img, int x, int y, int innerradius, int outerradius, yacySeed seed, String colorDot, String colorText) {
        String name = seed.getName().toUpperCase();
        if (name.length() < shortestName) shortestName = name.length();
        if (name.length() > longestName) longestName = name.length();
        int angle = (int) ((long) 360 * (seed.dhtDistance() / (yacySeed.maxDHTDistance / (long) 10000)) / (long) 10000);
        //System.out.println("Seed " + seed.hash + " has distance " + seed.dhtDistance() + ", angle = " + angle);
        int linelength = 20 + outerradius * (20 * (name.length() - shortestName) / (longestName - shortestName) + (Math.abs(seed.hash.hashCode()) % 20)) / 60;
        if (linelength > outerradius) linelength = outerradius;
        int dotsize = 6 + 2 * (int) (seed.getLinkCount() / 500000L);
        if (dotsize > 18) dotsize = 18;
        img.setColor(colorDot);
        img.arcDot(x, y, innerradius, dotsize, angle);
        img.setColor(colorText);
        img.arcLine(x, y, innerradius + 18, innerradius + linelength, angle);
        img.arcPrint(x, y, innerradius + linelength, angle, name);
    }
    
}
