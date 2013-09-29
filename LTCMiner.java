/*!
   LTCMiner -- LTCMiner for ZTEX USB-FPGA Modules
   Copyright (C) 2011-2012 ZTEX GmbH
   http://www.ztex.de

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License version 3 as
   published by the Free Software Foundation.

   This program is distributed in the hope that it will be useful, but
   WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
   General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, see http://www.gnu.org/licenses/.
!*/

/* TODO: 
 * HUP signal
 * rollntime / expire oder strutm
 * backup pool: prioritaet veraendern, timeout einstellen
 *
 *
 * TODO: 
 * scrypt remove all dependencies from midstate
 */  
 


import java.io.*;
import java.util.*;
import java.net.*;
import java.security.*;
import java.text.*;
import java.util.zip.*;

import ch.ntb.usb.*;

import ztex.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

import static java.lang.System.arraycopy;
import static java.lang.Integer.rotateLeft;

// *****************************************************************************
// ******* ParameterException **************************************************
// *****************************************************************************
// Exception the prints a help message
class ParameterException extends Exception {
    public final static String helpMsg = new String (
		"Parameters:\n"+
		"    -host <string>    Host URL (default: http://127.0.0.1:8332)\n" +
		"    -u <string>       RPC User name\n" + 
		"    -p <string>       RPC Password\n" + 
		"    -lp <url> <user name> <password> \n" + 
		"                      URL, user name and password of a long polling server (determined automatically by default) \n"+
		"    -l <log file>     Log file (default: LTCMiner.log) \n" +
		"    -l2 <log file>    Secondary log file, logs everything but statistics \n" +
		"    -bl <log file>    Log of submitted blocks file \n" +
		"    -c <file name>    Secondary command input file, can be a named pipe \n" + 
		"    -m s|t|p|c        Set single mode, test mode, programming mode or cluster mode\n"+
		"                      Single mode: runs LTCMiner on a single board (default mode)\n" +
		"                      Test mode: tests a board using some test data\n" +
		"                      Programming mode: programs device with the given firmware\n" +
		"                      Cluster mode: runs LTCMiner on all programmed boards\n" +
		"    -v                Be verbose\n" +
		"    -h                This help\n" +
		"Parameters in single mode, test mode and programming mode\n"+
		"    -i                Print bus info\n" 
	);
		
    
    public ParameterException (String msg) {
	super( msg + "\n" + helpMsg );
    }
}

/* *****************************************************************************
   ******* ParserException *****************************************************
   ***************************************************************************** */   
class ParserException extends Exception {
    public ParserException(String msg ) {
	super( msg );
    }
}    

/* *****************************************************************************
   ******* FirmwareException ***************************************************
   ***************************************************************************** */   
class FirmwareException extends Exception {
    public FirmwareException(String msg ) {
	super( msg );
    }
}    


// *****************************************************************************
// ******* MsgObj *************************************************************
// *****************************************************************************
interface MsgObj {
    public void msg(String s);
}
    

// *****************************************************************************
// ******* LTCMinerThread ******************************************************
// *****************************************************************************
class LTCMinerThread extends Thread {
    private Vector<LTCMiner> miners = new Vector<LTCMiner>();
    private String busName;
    private PollLoop pollLoop = null;
    
// ******* constructor *********************************************************
    public LTCMinerThread( String bn ) {
	busName = bn;
    }

// ******* add *****************************************************************
    public void add ( LTCMiner m ) {
	synchronized ( miners ) {
	    miners.add ( m );
	    m.name = busName + ": " + m.name;
	}

	if ( pollLoop==null ) {
	    LTCMiner.printMsg2("Starting mining thread for bus " + busName);
	    start();
	}
    }

// ******* size ****************************************************************
    public int size () {
	return miners.size();
    }

// ******* elementAt ***********************************************************
    public LTCMiner elementAt ( int i ) {
	return miners.elementAt(i);
    }

// ******* find ****************************************************************
    public LTCMiner find ( int dn ) {
	for (int i=0; i<miners.size(); i++ ) {
	    if ( (miners.elementAt(i).ztex().dev().dev().getDevnum() == dn) )
		return miners.elementAt(i);
	}
	return null;
    }

// ******* busName *************************************************************
    public String busName () {
	return busName;
    }

// ******* running *************************************************************
    public boolean running () {
	return pollLoop != null;
    }

// ******* run *****************************************************************
    public void run () {
	pollLoop = new PollLoop(miners);
	pollLoop.run();
	pollLoop = null;
    }

// ******* printInfo ************************************************************
    public void printInfo ( ) {
	if ( pollLoop != null )
	    pollLoop.printInfo( busName );
    }


// ******* disconnect ***********************************************************
    public int disconnect ( String ss, Vector<LTCMiner> allMiners ) {
	int i=0;
	synchronized ( miners ) {
	    for (int j=miners.size()-1; j>=0; j-- ) {
		LTCMiner m = miners.elementAt(j);
		if ( ss.equals(m.ztex().dev().snString()) ) {
		    LTCMiner.printMsg("Disconnecting "+m.name);
		    if ( allMiners != null )
			allMiners.removeElement(m);
		    m.suspend();
		    miners.removeElementAt(j);
		    i+=1;
		}
	    }
	}
	return i;
    }

}


// *****************************************************************************
// ******* LogString ***********************************************************
// *****************************************************************************
class LogString {
    public Date time;
    public String msg;
    
    public LogString(String s) {
	time = new Date();
	msg = s;
    }
}


// *****************************************************************************
// ******* PollLoop ************************************************************
// *****************************************************************************
class PollLoop {
    public static boolean scanMode = false;
    
    private double usbTime = 0.0;
    private double networkTime = 0.0;
    private double timeW = 1e-6;
    private Vector<LTCMiner> v;
    public static final long minQueryInterval = 250;

// ******* constructor *********************************************************
    public PollLoop ( Vector<LTCMiner> pv ) {
	v = pv;
    }
	
// ******* run *****************************************************************
    public void run ( ) {
	int maxIoErrorCount = (int) Math.round( (LTCMiner.rpcCount > 1 ? 2 : 4)*LTCMiner.connectionEffort );
	int ioDisableTime = LTCMiner.rpcCount > 1 ? 60 : 30;
	
	while ( v.size()>0 ) {
	    long t0 = new Date().getTime();
	    long tu = 0;

	    if ( ! scanMode ) {
		synchronized ( v ) {
		    for ( int i=v.size()-1; i>=0; i-- ) {
			LTCMiner m = v.elementAt(i);
			
			m.usbTime = 0;
			
			try { 
			    if ( ! m.suspended ) {
				if ( m.checkUpdate() && m.getWork() ) { // getwork calls getNonces
			    	    m.dmsg("Got new work");
			    	    m.sendData();
				}
				else {
			    	    m.getNonces();
				}
				//m.updateFreq();
				m.printInfo(false);
			    }
			}
			catch ( IOException e ) {
			    m.ioErrorCount[m.rpcNum]++;
			    if ( m.ioErrorCount[m.rpcNum] >= maxIoErrorCount ) {
    			        m.msg("Error: "+e.getLocalizedMessage() +": IOException: Disabling URL " + m.rpcurl[m.rpcNum] + " for " + ioDisableTime + "s");
    			        m.disableTime[m.rpcNum] = new Date().getTime() + ioDisableTime*1000;
    			        m.ioErrorCount[m.rpcNum] = 0;
			    }
    			}
			catch ( ParserException e ) {
    			    m.msg("Error: "+e.getLocalizedMessage() +": ParseException: Disabling URL " + m.rpcurl[m.rpcNum] + " for 60s");
    			    m.disableTime[m.rpcNum] = new Date().getTime() + 60000;
    			}
			catch ( NumberFormatException e ) {
    			    m.msg("Error: "+e.getLocalizedMessage() +": NumberFormatException Disabling URL " + m.rpcurl[m.rpcNum] + " for 60s");
    			    m.disableTime[m.rpcNum] = new Date().getTime() + 60000;
    			}
			catch ( IndexOutOfBoundsException e ) {
    			    m.msg("Error: "+e.getLocalizedMessage() +": IndexOutofBoundException Disabling URL " + m.rpcurl[m.rpcNum] + " for 60s");
    			    m.disableTime[m.rpcNum] = new Date().getTime() + 60000;
    			}
			catch ( Exception e ) {
    			    m.msg("Error: "+e.getLocalizedMessage()+": Disabling device");
    			    m.fatalError = "Error: "+e.getLocalizedMessage()+": Device disabled since " + LTCMiner.dateFormat.format( new Date() );
    			    v.removeElementAt(i);
			}

    			tu += m.usbTime;
   		    }
		}

		t0 = new Date().getTime() - t0;
		usbTime = usbTime * 0.9998 + tu;
		networkTime = networkTime * 0.9998 + t0 - tu;
		timeW = timeW * 0.9998 + 1;
	    }
	    else {
		t0 = 0;
	    }
	    
	    t0 = minQueryInterval - t0;
	    if ( t0 > 5 ) {
		try {
		    Thread.sleep( t0 );
		}
		catch ( InterruptedException e) {
		}	 
	    }
	}
    }

// ******* printInfo ***********************************************************
    public void printInfo( String name ) {
	int oc = 0;
	double gt=0.0, gtw=0.0, st=0.0, stw=0.0;
	for ( int i=v.size()-1; i>=0; i-- ) {
	    LTCMiner m = v.elementAt(i);
	    oc += m.overflowCount;
	    m.overflowCount = 0;
	    
	    st += m.submitTime;
	    stw += m.submitTimeW;
	    
	    gt += m.getTime;
	    gtw += m.getTimeW;
	}
	    
	LTCMiner.printMsg2(name + ": poll loop time: " + Math.round((usbTime+networkTime)/timeW) + "ms (USB: " + Math.round(usbTime/timeW) + "ms network: " + Math.round(networkTime/timeW) + "ms)   getwork time: " 
		+  Math.round(gt/gtw) + "ms  submit time: " +  Math.round(st/stw) + "ms" );
	if ( oc > 0 )
	    LTCMiner.printMsg( name + ": Warning: " + oc + " overflows occured. This is usually caused by a slow network connection." );
    }
}


// *****************************************************************************
// ******* NewBlockMonitor *****************************************************
// *****************************************************************************
class NewBlockMonitor extends Thread implements MsgObj {
    public int newCount = -1;
    
    public boolean running;
    
    private static final int minLongPollInterval = 250; // in ms

    private byte[] prevBlock = new byte[32];
    private byte[] dataBuf = new byte[128];
    
    private Vector<LogString> logBuf = new Vector<LogString>();
    
    public static boolean submitOld;
    
// ******* constructor *********************************************************
    public NewBlockMonitor( ) {
	start();
    }

// ******* checkNew ************************************************************
    synchronized public boolean checkNew ( byte[] data ) throws NumberFormatException {
	if ( data.length < 36 )
	    throw new NumberFormatException("Invalid length of data " + data.length);

	boolean n = false;

	data = LTCMiner.endianSwitch(data);

	for ( int i=0; i<32; i++ ) {
	    n = n | ( data[i+4] != prevBlock[i] );
	    prevBlock[i] = data[i+4];
	}
	if ( n ) {
	    newCount += 1;
	    submitOld = true;
	    if ( newCount > 0 )
		msg("New block detected by block monitor");
	}
	    
	return n;
    }

// ******* run *****************************************************************
    public void run () {
	running = true;
	
	boolean enableLP = true;
	boolean warnings = true;
	long enableLPTime = 0;
	
	submitOld = true;

	while ( running ) {
	    long t = new Date().getTime();
	    
	    if ( LTCMiner.longPollURL!=null && enableLP && t>enableLPTime) {
		try {
		    msg("info: LP");
		    String req = LTCMiner.bitcoinRequest(this, LTCMiner.longPollURL, LTCMiner.longPollUser, LTCMiner.longPollPassw, "getwork", "");
		    LTCMiner.hexStrToData(LTCMiner.jsonParse(req, "data"), dataBuf);
		    dataBuf = LTCMiner.endianSwitch(dataBuf);

		    submitOld = true;
		    String so = null;
		    try {
			so = LTCMiner.jsonParse(req, "submitold");
			if ( so.equalsIgnoreCase("false") )
			    submitOld = false;
		    }
		    catch ( Exception e ) {
		    }
		    
		    for ( int i=0; i<32; i++ ) {
			prevBlock[i] = dataBuf[i+4];
		    }
		    newCount += 1;
		    msg( "New block detected by long polling" + ( so == null ? "" : " (submitold = " + so + ")" ) );
		}
		catch ( MalformedURLException e ) {
		    msg("Warning: " + e.getLocalizedMessage() + ": disabling long polling");
		    enableLP = false;
		}
		catch ( IOException e ) {
		    if ( new Date().getTime() < t+500 ) {
			msg("Warning: " + e.getLocalizedMessage() + ": disabling long polling fo 60s");
			enableLPTime = new Date().getTime() + 60000;
		    }
		}
		catch ( Exception e ) {
		    if ( warnings )
			msg("Warning: " + e.getLocalizedMessage());
		    warnings = false;
		}
	    }
	    
	    if ( LTCMiner.longPollURL==null )
		enableLPTime = new Date().getTime() + 2000;
	    
	    t += minLongPollInterval - new Date().getTime();
	    if ( t > 5 ) {
		try {
		    Thread.sleep( t );
		}
		catch ( InterruptedException e) {
		}	 
	    }
	}
    }

// ******* msg *****************************************************************
    public void msg(String s) {
	synchronized ( logBuf ) {
	    logBuf.add( new LogString( s ) );
	}
    }

// ******* print ***************************************************************
    public void print () {
	synchronized ( logBuf ) {
	    for ( int j=0; j<logBuf.size(); j++ ) {
	        LogString ls = logBuf.elementAt(j);
	        System.out.println( ls.msg );
		if ( LTCMiner.logFile != null ) {
		    LTCMiner.logFile.println( LTCMiner.dateFormat.format(ls.time) + ": " + ls.msg );
		}
		if ( LTCMiner.logFile2 != null && !ls.msg.substring(0,18).equals("New block detected") ) {
		    LTCMiner.logFile2.println( LTCMiner.dateFormat.format(ls.time) + ": " + ls.msg );
		}
	    }
	    logBuf.clear();
	}
    }
}

// *****************************************************************************
// *****************************************************************************
// ******* LTCMiner ************************************************************
// *****************************************************************************
// *****************************************************************************
class LTCMiner implements MsgObj  {

// *****************************************************************************
// ******* static methods ******************************************************
// *****************************************************************************
    static final int maxRpcCount = 32;
    static String[] rpcurl = new String[maxRpcCount];
    static String[] rpcuser = new String[maxRpcCount];
    static String[] rpcpassw = new String[maxRpcCount];
    static int rpcCount = 1;

    static String longPollURL = null;
    static String longPollUser = "";
    static String longPollPassw = "";
    
    static int bcid = -1;

    static String firmwareFile = null;
    static boolean printBus = false;

    public final static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    
    static NewBlockMonitor newBlockMonitor = null;
    static PrintStream logFile = null;
    static PrintStream logFile2 = null;
    static PrintStream blkLogFile = null;

    static InputStream in2 = null;
    static String in2FileName = null;
    
    static double connectionEffort = 2.0;
    
    static boolean forceEP0Config = false;
    
    static double overheatThreshold = 0.04;

    static double maxMaxErrorRate = 0.05;
    
    static double tempLimit = 65;  // in C
    
    static boolean targetCheck = false;
    
    static String filterSN = null;

    public static final String[] dummyFirmwareNames = {
	"USB-FPGA Module 1.15d (default)" ,
	"USB-FPGA Module 1.15x (default)" ,
	"USB-FPGA Module 1.15y (default)"
    };

    public static final int[] defaultFirmwarePID1 = {
	13 ,
	13 ,
	15
    };

    public static final String[] firmwareFiles = {
	"ztex_ufm1_15d4.ihx" ,
	"ztex_ufm1_15d4.ihx" ,
	"ztex_ufm1_15y1.ihx" 
    };
   
public static byte[] endianSwitch(byte[] bytes) {
	   //Method to switch the endianess of a byte array
	   byte[] bytes2 = new byte[bytes.length];
	   for(int i = 0; i < bytes.length;  i++){
		   bytes2[i] = bytes[bytes.length-i-1];
	   }
	   return bytes2;
}

//invert endian in chunk
public static byte[] chunkEndianSwitch(byte[] bytes) {
           //Method to properly switch the endianness of the header -- numbers must be treated as 32 bit chunks. Thanks to ali1234 for this.
           byte[] bytes2 = new byte[bytes.length];
           for(int i = 0; i < bytes.length;  i+=4){
                   bytes2[i] = bytes[i+3];
                   bytes2[i+1] = bytes[i+2];
                   bytes2[i+2] = bytes[i+1];
                   bytes2[i+3] = bytes[i];
           }
           return bytes2;
 }
    
// ******* printMsg *************************************************************
    public static void printMsg ( String msg ) {
	System.out.println( msg );
	if ( logFile != null )
	    logFile.println( dateFormat.format( new Date() ) + ": " + msg );
	if ( logFile2 != null )
	    logFile2.println( dateFormat.format( new Date() ) + ": " + msg );
    }

// ******* printMsg2 ************************************************************
    public static void printMsg2 ( String msg ) {
	System.out.println( msg );
	if ( logFile != null )
	    logFile.println( dateFormat.format( new Date() ) + ": " + msg );
    }

// ******* encodeBase64 *********************************************************
    public static String encodeBase64(String s) {
        return encodeBase64(s.getBytes());
    }

    public static String encodeBase64(byte[] src) {
	return encodeBase64(src, 0, src.length);
    }

    public static String encodeBase64(byte[] src, int start, int length) {
        final String charSet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	byte[] encodeData = new byte[64];
        byte[] dst = new byte[(length+2)/3 * 4 + length/72];
        int x = 0;
        int dstIndex = 0;
        int state = 0;
        int old = 0;
        int len = 0;
	int max = length + start;

	for (int i = 0; i<64; i++) {
	    byte c = (byte) charSet.charAt(i);
	    encodeData[i] = c;
	}
	
        for (int srcIndex = start; srcIndex<max; srcIndex++) {
	    x = src[srcIndex];
	    switch (++state) {
	    case 1:
	        dst[dstIndex++] = encodeData[(x>>2) & 0x3f];
		break;
	    case 2:
	        dst[dstIndex++] = encodeData[((old<<4)&0x30) 
	            | ((x>>4)&0xf)];
		break;
	    case 3:
	        dst[dstIndex++] = encodeData[((old<<2)&0x3C) 
	            | ((x>>6)&0x3)];
		dst[dstIndex++] = encodeData[x&0x3F];
		state = 0;
		break;
	    }
	    old = x;
	    if (++len >= 72) {
	    	dst[dstIndex++] = (byte) '\n';
	    	len = 0;
	    }
	}

	switch (state) {
	case 1: dst[dstIndex++] = encodeData[(old<<4) & 0x30];
	   dst[dstIndex++] = (byte) '=';
	   dst[dstIndex++] = (byte) '=';
	   break;
	case 2: dst[dstIndex++] = encodeData[(old<<2) & 0x3c];
	   dst[dstIndex++] = (byte) '=';
	   break;
	}
	return new String(dst);
    }

// ******* hexStrToData ********************************************************
    public static byte[] hexStrToData( String str ) throws NumberFormatException {
	if ( str.length() % 2 != 0 ) 
	    throw new NumberFormatException("Invalid length of string");
	byte[] buf = new byte[str.length() >> 1];
	for ( int i=0; i<buf.length; i++) {
	    buf[i] = (byte) Integer.parseInt( str.substring(i*2,i*2+2), 16);
	}
	return buf;
    }

    public static void hexStrToData( String str, byte[] buf ) throws NumberFormatException {
	if ( str.length()<buf.length*2 ) 
	    throw new NumberFormatException("Invalid length of string");
	for ( int i=0; i<buf.length; i++) {
	    buf[i] = (byte) Integer.parseInt( str.substring(i*2,i*2+2), 16);
	}
    }

// ******* hexStrToData2 ********************************************************
    public static void hexStrToData2( String str, byte[] buf ) throws NumberFormatException {
	if ( str.length()<buf.length*2 ) 
	    throw new NumberFormatException("Invalid length of string");
	for ( int i=0; i<buf.length; i++) {
	    buf[i] = (byte) (Integer.parseInt( str.substring(i*2,i*2+1), 16) + Integer.parseInt( str.substring(i*2+1,i*2+2), 16)*16);
	}
    }

// ******* dataToHexStr ********************************************************
    public static String dataToHexStr (byte[] data)  {
	final char hexchars[] = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
	char[] buf = new char[data.length*2];
	for ( int i=0; i<data.length; i++) {
	    buf[i*2+0] = hexchars[(data[i] & 255) >> 4];
	    buf[i*2+1] = hexchars[(data[i] & 15)];
	}
	return new String(buf);
    }

// ******* dataToInt **********************************************************
    public static int dataToInt (byte[] buf, int offs)  {
	if ( offs + 4 > buf.length )
	    throw new NumberFormatException("Invalid length of data");
	return (buf[offs+0] & 255) | ((buf[offs+1] & 255)<<8) | ((buf[offs+2] & 255)<<16) | ((buf[offs+3] & 255)<<24);
    }

// ******* intToData **********************************************************
    public static byte[] intToData (int n)  {
	byte[] buf = new byte[4];
	buf[0] = (byte) (n & 255);
	buf[1] = (byte) ((n >> 8) & 255);
	buf[2] = (byte) ((n >> 16) & 255);
	buf[3] = (byte) ((n >> 24) & 255);
	return buf;
    }

    public static void intToData (int n, byte[] buf, int offs) {
	buf[offs+0] = (byte) (n & 255);
	buf[offs+1] = (byte) ((n >> 8) & 255);
	buf[offs+2] = (byte) ((n >> 16) & 255);
	buf[offs+3] = (byte) ((n >> 24) & 255);
    }

    //question:
    //if i switch the endianess can I use the intToData?
    public static void intToDataL(int nonce, byte[] buf, int offs) {
   	buf[offs+3] = (byte) (nonce >>  0);
	buf[offs+2] = (byte) (nonce >>  8);
	buf[offs+1] = (byte) (nonce >> 16);
	buf[offs+0] = (byte) (nonce >> 24); 
    }

// ******* intToHexStr ********************************************************
    public static String intToHexStr (int n)  {
	return dataToHexStr( reverse( intToData ( n ) ) );
    }

// ******* reverse ************************************************************
    public static byte[] reverse (byte[] data)  {
	byte[] buf = new byte[data.length];
	for ( int i=0; i<data.length; i++) 
	    buf[data.length-i-1] = data[i];
	return buf;
    }

// ******* jsonParse ***********************************************************
// does not work if parameter name is a part of a parameter value
    public static String jsonParse (String response, String parameter) throws ParserException {
	int lp = parameter.length();
	int i = 0;
	while ( i+lp<response.length() && !parameter.equalsIgnoreCase(response.substring(i,i+lp)) )
	    i++;
	i+=lp;
	if ( i>=response.length() )
	    throw new ParserException( "jsonParse: Parameter `"+parameter+"' not found" );
	while ( i<response.length() && response.charAt(i) != ':' )
	    i++;
	i+=1;
	while ( i<response.length() && (byte)response.charAt(i) <= 32 )
	    i++;
	if ( i>=response.length() )
	    throw new ParserException( "jsonParse: Value expected after `"+parameter+"'" );
	int j=i;
	if ( i<response.length() && response.charAt(i)=='"' ) {
	    i+=1;
	    j=i;
	    while ( j<response.length() && response.charAt(j) != '"' )
		j++;
	    if ( j>=response.length() )
		throw new ParserException( "jsonParse: No closing `\"' found for value of paramter `"+parameter+"'" );
	}
	else { 
	    while ( j<response.length() && response.charAt(j) != ',' && response.charAt(j) != /*{*/'}'  ) 
		j++;
	}
	return response.substring(i,j);
    } 


// ******* checkSnString *******************************************************
// make sure that snString is 10 chars long
    public static String checkSnString ( String snString ) {
    	if ( snString.length()>10 ) {
    	    snString = snString.substring(0,10);
	    System.err.println( "Serial number too long (max. 10 characters), truncated to `" + snString + "'" );
	}
	while ( snString.length()<10 )
	    snString = '0' + snString;
	return snString;
    }


// ******* getType *************************************************************
    private static String getType ( ZtexDevice1 pDev ) {
	byte[] buf = new byte[64];
	try {
	    Ztex1v1 ztex = new Ztex1v1 ( pDev );
    	    ztex.vendorRequest2( 0x82, "Read descriptor", 0, 0, buf, 64 );
    	    if ( buf[0] < 1 || buf[0] > 5 ) 
    		throw new FirmwareException("Invalid LTCMiner descriptor version");

	    int i0 = buf[0] > 4 ? 11 : ( buf[0] > 2 ? 10 : 8 );
    	    int i = i0;
    	    while ( i<64 && buf[i]!=0 )
    		i++;
    	    if ( i < i0+1 )
    		throw new FirmwareException("Invalid bitstream file name");

    	    return new String(buf, i0, i-i0);
    	}
    	catch ( Exception e ) {
	    System.out.println("Warning: "+e.getLocalizedMessage() );
	}
	return null;
    }


    public static void printBus ( ZtexScanBus1 bus ) {
	for (int i=0; i<bus.numberOfDevices(); i++ ) {
	    ZtexDevice1 dev = bus.device(i);
	    System.out.println( i + ": " + dev.toString() );
	    try {
	        byte buf[] = new byte[6];
	        new Ztex1v1(dev).macRead(buf);
	        System.out.println("   MAC address: " + dataToHexStr(buf)); 
	    }
	    catch (Exception e) {
	    }
	}
    }

// *****************************************************************************
// ******* non-static methods **************************************************
// *****************************************************************************
    private Ztex1v1 ztex = null;
    private int fpgaNum = 0;
    
    public int numNonces, offsNonces, freqM, freqMDefault, freqMaxM, extraSolutions;
    public double freqM1;
    public double hashesPerClock;
    private String bitFileName = null;
    public String name;
    public String fatalError = null;
    private boolean suspendSupported = false;

    public int ioErrorCount[] = new int[maxRpcCount];
    public long disableTime[] = new long[maxRpcCount];
        
    public int rpcNum = 0;
    private int prevRpcNum = 0;
    
    public boolean verbose = false;
    public boolean clusterMode = false;
    
    public Vector<LogString> logBuf = new Vector<LogString>();

    private byte[] dataBuf = new byte[128];
    private byte[] dataBuf2 = new byte[128];
    private byte[] sendBuf = new byte[84]; 
    private byte[] hashBuf = hexStrToData("00000000000000000000000000000000000000000000000000000000000000000000008000000000000000000000000000000000000000000000000000010000");
    private byte[] targetBuf = hexStrToData("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff000000");
    
    private int newCount = 0;

    public boolean isRunning = false;
    public boolean suspended = false;
    
    
    public int[] lastGoldenNonces = { 0, 0, 0, 0, 0, 0, 0, 0 };
    public int[] goldenNonce, nonce, hash7;
    public int submittedCount = 0;
    public double totalSubmittedCount = 0.0;
    public long startTime, startTimeAdjust;
    
    public int overflowCount = 0;
    public long usbTime = 0;
    public double getTime = 0.0; 
    public double getTimeW = 1e-6; 
    public double submitTime = 0.0; 
    public double submitTimeW = 1e-6; 
    
    public long maxPollInterval = 20000;
    public long infoInterval = 15000;
    
    public long lastGetWorkTime = 0;
    public long ignoreErrorTime = 0;
    public long lastInfoTime = 0;
        
    public double[] errorCount = new double[256];
    public double[] errorWeight = new double[256];
    public double[] errorRate = new double[256];
    public double[] maxErrorRate = new double[256];
    public final double errorHysteresis = 0.1; // in frequency steps
    
    
    private double maxHashRate = 0;
    
    private int numberOfFpgas = 0;
    private int[] fpgaMap;
    
// ******* LTCMiner ************************************************************
// constructor
    public LTCMiner ( Ztex1v1 pZtex, String firmwareFile, boolean v ) throws UsbException, FirmwareException, NoSuchAlgorithmException {
	
	verbose = v;

	ztex = pZtex;
	ztex.tempSensorUpdateInterval = 1000;
	ztex.enableExtraFpgaConfigurationChecks = true;

	String snString=null;
	if ( ( ztex.dev().productId(2)==0) && (firmwareFile==null) ) {
	    for ( int j=0; j<defaultFirmwarePID1.length; j++ )
		if ( defaultFirmwarePID1[j]==ztex.dev().productId(1) && ztex.dev().productString().equals(dummyFirmwareNames[j]) ) 
		    firmwareFile = firmwareFiles[j];
	    if ( firmwareFile != null ) {
	        msg("Using firmware `" + firmwareFile + "'" + " for `" + ztex.dev().productString() +"'" );
	        snString = ztex.dev().snString();
	    }
	}

        if ( firmwareFile != null ) {
    	    try {
    		ZtexIhxFile1 ihxFile = new ZtexIhxFile1( firmwareFile );
		if ( snString != null ) 
		    ihxFile.setSnString( snString );
		ztex.uploadFirmware( ihxFile, false );
    	    }
    	    catch ( Exception e ) {
    		throw new FirmwareException ( e.getLocalizedMessage() );
    	    }
    	}
    	    
        if ( ! ztex.valid() || ztex.dev().productId(0)!=10 || ztex.dev().productId(2)!=1 )
    	    throw new FirmwareException("Wrong or no firmware");
    	    
	getDescriptor();    	    
	
	goldenNonce = new int[numNonces*(1+extraSolutions)];
	nonce = new int[numNonces];
	hash7 = new int[numNonces];
	
	name = bitFileName+"-"+ztex.dev().snString();
    	msg( "New device: "+ descriptorInfo() );
	try {
	    byte buf[] = new byte[6];
	    ztex.macRead(buf);
	    msg("MAC address: " + dataToHexStr(buf)); 
	}
	catch (Exception e) {
	    msg("No mac address support"); 
	}
    	
    	
//    	long d = Math.round( 2500.0 / (freqM1 * (freqMaxM+1) * numNonces) * 1000.0 );
//    	if ( d < maxPollInterval ) maxPollInterval=d;

	numberOfFpgas = 0;
	try {
	    fpgaMap = new int[ztex.numberOfFpgas()];
    	    for (int i=0; i<ztex.numberOfFpgas(); i++ ) {
    		try {
		    ztex.selectFpga(i);
		    msg("FPGA "+ (i+1) + ": configuration time: " + ( forceEP0Config ? ztex.configureFpgaLS( "fpga/"+bitFileName+".bit" , true, 2 ) : ztex.configureFpga( "fpga/"+bitFileName+".bit" , true, 2 ) ) + " ms");
    		    try {
    			Thread.sleep( 100 );
    		    }
		    catch ( InterruptedException e) {
    		    } 
		    fpgaMap[numberOfFpgas] = i;
		    numberOfFpgas += 1;
    		}
		catch ( Exception e ) {
		    msg( "Error configuring FPGA " + i + ": " + e.getLocalizedMessage() );
		}
	    }
	}
        catch ( InvalidFirmwareException e ) {
    	    throw new FirmwareException( e.getLocalizedMessage() );
    	}
	    
	if ( numberOfFpgas < 1 )
	    throw new FirmwareException("No FPGA's found");

	fpgaNum = fpgaMap[0];
	name += "-" + (fpgaNum+1);
    	msg( "New FPGA" );
	freqM = -1;
	updateFreq();
	
	lastInfoTime = new Date().getTime();
	
	for (int i=0; i<255; i++) {
	    errorCount[i] = 0;
	    errorWeight[i] = 0;
	    errorRate[i] = 0;
	    maxErrorRate[i] = 0;
	}
	maxHashRate = freqMDefault + 1.0;
	
	startTime = new Date().getTime();
	startTimeAdjust = startTime;
	
	for (int i=0; i<rpcCount; i++) {
	    disableTime[i] = 0;
	    ioErrorCount[i] = 0;
	}
	if ( newBlockMonitor == null ) {
	    newBlockMonitor = new NewBlockMonitor();
	}

    }


    public LTCMiner ( ZtexDevice1 pDev, String firmwareFile, boolean v ) throws UsbException, FirmwareException, NoSuchAlgorithmException {
	this ( new Ztex1v1 ( pDev ), firmwareFile, v );
    }
    

    public LTCMiner ( Ztex1v1 pZtex, int pFpgaNum, boolean v ) throws UsbException, FirmwareException, NoSuchAlgorithmException {
	verbose = v;

	ztex  = pZtex;
	fpgaNum = pFpgaNum;

        if ( ! ztex.valid() || ztex.dev().productId(0)!=10 || ztex.dev().productId(2)!=1 || ( ztex.dev().productId(3)<1 && ztex.dev().productId(3)>2 ) )
    	    throw new FirmwareException("Wrong or no firmware");
    	    
	getDescriptor();    	    

	goldenNonce = new int[numNonces*(1+extraSolutions)];
	nonce = new int[numNonces];
	hash7 = new int[numNonces];
	
	name = bitFileName+"-"+ztex.dev().snString()+"-"+(fpgaNum+1);
    	
    	try {
    	    msg( "New FPGA" );
	    freqM = -1;
	    updateFreq();
	    
	    lastInfoTime = new Date().getTime();
	}
	catch ( Exception e ) {
	    throw new FirmwareException ( e.getLocalizedMessage() );
	}
	
	
	for (int i=0; i<255; i++) {
	    errorCount[i] = 0;
	    errorWeight[i] = 0;
	    errorRate[i] = 0;
	    maxErrorRate[i] = 0;
	}
	maxHashRate = freqMDefault + 1.0;
	
	startTime = new Date().getTime();
	startTimeAdjust = startTime;
	
	for (int i=0; i<rpcCount; i++) {
	    disableTime[i] = 0;
	    ioErrorCount[i] = 0;
	}
	
    }

// ******* ztex ****************************************************************
    public Ztex1v1 ztex() {
	return ztex;
    }

// ******* numberofFpgas *******************************************************
    public int numberOfFpgas() {
	return numberOfFpgas;
    }

// ******* selectFpga **********************************************************
    public void selectFpga() throws UsbException, InvalidFirmwareException, IndexOutOfBoundsException {
	ztex.selectFpga(fpgaNum);
    }

// ******* fpgaNum *************************************************************
    public int fpgaNum() {
	return fpgaNum;
    }

    public int fpgaNum(int n) throws IndexOutOfBoundsException { // only valid for root miner
	if ( n<0 || n>=numberOfFpgas )
    	    throw new IndexOutOfBoundsException( "fpgaNum: Invalid FPGA number" );
	return fpgaMap[n];
    }

// ******* msg *****************************************************************
    public void msg(String s) {
	if ( clusterMode ) {
	    synchronized ( logBuf ) {
		logBuf.add( new LogString( s ) );
	    }
	}
	else {
	    printMsg( ( name!=null ? name + ": " : "" ) + s );
	}
    }


// ******* dmsg *****************************************************************
    void dmsg(String s) {
	if ( verbose )
	    msg(s);
    }

// ******* print ***************************************************************
    public void print () {
	synchronized ( logBuf ) {
	    for ( int j=0; j<logBuf.size(); j++ ) {
	        LogString ls = logBuf.elementAt(j);
	        System.out.println( name + ": " + ls.msg );
		if ( logFile != null ) {
		    logFile.println( dateFormat.format(ls.time) + ": " + name + ": " + ls.msg );
		}
		if ( logFile2 != null ) {
		    logFile2.println( dateFormat.format(ls.time) + ": " + name + ": " + ls.msg );
		}
	    }
	    logBuf.clear();
	}
    }

// ******* httpGet *************************************************************
    public static String httpGet(MsgObj msgObj, String url, String user, String passw, String request) throws MalformedURLException, IOException {
	HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout((int) Math.round(2000.0*LTCMiner.connectionEffort));
        con.setReadTimeout(url == longPollURL ? 1000000 : (int) Math.round(2000.0*LTCMiner.connectionEffort));
        con.setRequestProperty("Authorization", "Basic " + encodeBase64(user + ":" + passw));
        con.setRequestProperty("Accept-Encoding", "gzip,deflate");
        con.setRequestProperty("Content-Type", "application/json");
	con.setRequestProperty("Cache-Control", "no-cache");
        con.setRequestProperty("User-Agent", "ztexLTCMiner");
        con.setRequestProperty("Content-Length", "" + request.length());
        con.setRequestProperty("X-Mining-Extensions", "longpoll submitold"); //remove midstate scrypt
        con.setUseCaches(false);
        con.setDoInput(true);
        con.setDoOutput(true);
        
        // Send request
        OutputStreamWriter wr = new OutputStreamWriter ( con.getOutputStream ());
        wr.write(request);
        wr.flush();
        wr.close();

        // read response header
        String str = con.getHeaderField("X-Reject-Reason");
        if( str != null && ! str.equals("") && ! str.equals("high-hash") && ! str.equals("stale-prevblk") && ! str.equals("duplicate") ) {
            msgObj.msg("Warning: Rejected block: " + str);
        } 
        // read response header
    	str = con.getHeaderField("X-Long-Polling");
        if ( str != null && ! str.equals("") && longPollURL==null ) {
    	    synchronized ( LTCMiner.newBlockMonitor ) {
    		if ( longPollURL==null ) {
    		    longPollURL = (str.length()>7 && str.substring(0,4).equalsIgnoreCase("http") ) ? str : url+str;
    		    msgObj.msg("Using LongPolling URL " + longPollURL);
    		    longPollUser = user;
    		    longPollPassw = passw;
    		}
    	    }
        }

 
       // read response	
        InputStream is;
        if ( con.getContentEncoding() == null )
    	    is = con.getInputStream();
    	else if ( con.getContentEncoding().equalsIgnoreCase("gzip") )
    	    is = new GZIPInputStream(con.getInputStream());
    	else if (con.getContentEncoding().equalsIgnoreCase("deflate") )
            is = new InflaterInputStream(con.getInputStream());
        else
    	    throw new IOException( "httpGet: Unknown encoding: " + con.getContentEncoding() );

        byte[] buf = new byte[1024];
        StringBuffer response = new StringBuffer(); 
        int len;
        while ( (len = is.read(buf)) > 0 ) {
            response.append(new String(buf,0,len));
        }
        is.close();
        con.disconnect();
        
        return response.toString();
    }

// ******* bitcoinRequest ******************************************************
    public static String bitcoinRequest( MsgObj msgObj, String url, String user, String passw, String request, String params) throws MalformedURLException, IOException {
	bcid += 1;
	return httpGet( msgObj, url, user, passw, "{\"jsonrpc\":\"1.0\",\"id\":" + bcid + ",\"method\":\""+ request + "\",\"params\":["+ (params.equals("") ? "" : ("\""+params+"\"")) + "]}" );
    }

    public String bitcoinRequest( String request, String params) throws MalformedURLException, IOException {
	String s = bitcoinRequest( this, rpcurl[rpcNum], rpcuser[rpcNum], rpcpassw[rpcNum], request, params );
        ioErrorCount[rpcNum] = 0;
        return s;
    }


// ******* getWork *************************************************************
    public boolean getWork() throws UsbException, MalformedURLException, IOException, ParserException {

	long t = new Date().getTime();
    
	int i = 0;
	while ( i<rpcCount && (disableTime[i]>t) ) 
	    i++;
	if ( i >= rpcCount )
	    return false;

	rpcNum = i;	
	String response = bitcoinRequest("getwork","" );
	t = new Date().getTime() - t;
	getTime = getTime * 0.99 + t;
	getTimeW = getTimeW * 0.99 + 1;

        try {
	    hexStrToData(jsonParse(response,"data"), dataBuf2);
	    newBlockMonitor.checkNew( dataBuf2 );
	}
	catch ( NumberFormatException e ) {
	}

	if ( newCount >= newBlockMonitor.newCount || newBlockMonitor.submitOld ) {
	    while ( getNonces() ) {}
        }

	newCount = newBlockMonitor.newCount;
	
 	
	try {
	    hexStrToData(jsonParse(response,"data"), dataBuf);
	    dmsg("fresh dataBuf " + dataToHexStr(dataBuf));
	    dataBuf = chunkEndianSwitch(dataBuf);
	    //dmsg("little endian dataBuf " + dataToHexStr(dataBuf));
	}
	catch ( NumberFormatException e ) {
	    throw new ParserException( e.getLocalizedMessage() );
	}

	
	
	try {
	    if ( targetCheck ) {
		hexStrToData(jsonParse(response,"target"), targetBuf);
	    }
	    else {
		hexStrToData("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff000000", targetBuf);
	    }
	}
	catch ( Exception e ) {
	    hexStrToData("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff000000", targetBuf);	
	}
	
	targetBuf = endianSwitch(targetBuf);
	
	lastGetWorkTime = new Date().getTime();
	prevRpcNum = i;
	return true;
    }

// ******* submitWork **********************************************************
    public void submitWork( int n ) throws MalformedURLException, IOException {
	long t = new Date().getTime();

	dataBuf2[79] = (byte) (n >>  0);
	dataBuf2[78] = (byte) (n >>  8);
	dataBuf2[77] = (byte) (n >> 16);
	dataBuf2[76] = (byte) (n >> 24);

	dmsg( "Submitting new nonce " + intToHexStr(n) + ":" +  n );
	dmsg( dateFormat.format( new Date() ) + ": " + name + ": submitted " + dataToHexStr(dataBuf2) + " to " + rpcurl[rpcNum]);
	String response = bitcoinRequest( "getwork", dataToHexStr(dataBuf2));
	String err = null;
	try {
	    err = jsonParse(response,"error");
	}
	catch ( ParserException e ) {
	}
	if ( err!=null && !err.equals("null") && !err.equals("") ) 
	    msg( "Error attempting to submit new nonce: " + err );

	for (int i=lastGoldenNonces.length-1; i>0; i-- )
	    lastGoldenNonces[i]=lastGoldenNonces[i-1];
	lastGoldenNonces[0] = n;

	t = new Date().getTime() - t;
	submitTime = submitTime * 0.99 + t;
	submitTimeW = submitTimeW * 0.99 + 1;
    }

// ******* initWork **********************************************************
    public void initWork (byte[] data) {
	if ( data.length != 80 )
	    throw new NumberFormatException("Invalid length of data " + data.length);
	for (int i=0; i<80; i++)
	    dataBuf[i] = data[i];
    }
// ******* compareWithTarget ***************************************************
    // returns true if smaller than or equal to target
    public boolean compareWithTarget(int nonce) throws NumberFormatException {
      try {
    	  Hasher hasher = new Hasher();
    	  byte[] hash = hasher.hash(dataBuf, nonce);
    	  for (int i = hash.length - 1; i >= 0; i--) {
    		if ((hash[i] & 0xff) > (targetBuf[i] & 0xff))
    			return false;
    		if ((hash[i] & 0xff) < (targetBuf[i] & 0xff))
    			return true;
    	  }
    	  return true;    	  
      }catch (GeneralSecurityException e) {
			return false;
	 }
	 
    }

// ******* sendData ***********************************************************
//  for litecoin send 80 bytes of the 128 byte data plus 4 bytes of 32 byte target
    public void sendData () throws UsbException {
	final byte targ[] = new byte[] {0x00,0x00,0x7f,(byte)0xff};
	for ( int i=0; i < targ.length; i++ ) 
	    sendBuf[i] = targ[i];
	for ( int i=0; i < 80; i++)
	    sendBuf[i+4] = dataBuf[i]; 

	System.out.println("DATA TO FPGA: " + dataToHexStr(sendBuf));

	long t = new Date().getTime();
	synchronized (ztex) {
	    try {
		selectFpga();
	    }
	    catch ( InvalidFirmwareException e )  {
		// shouldn't occur
	    }
    	    ztex.vendorCommand2( 0x80, "Send hash data", 0, 0, sendBuf, 84 );
    	}
        usbTime += new Date().getTime() - t;
        
        ignoreErrorTime = new Date().getTime() + 500; // ignore errors for next 1s
	for ( int i=0; i<numNonces; i++ ) 
	    nonce[i] = 0;
        isRunning = true;
    }

// ******* setFreq *************************************************************
    public void setFreq (int m) throws UsbException {
	if ( m > freqMaxM ) m = freqMaxM;

	long t = new Date().getTime();
	synchronized (ztex) {
	    try {
		selectFpga();
	    }
	    catch ( InvalidFirmwareException e )  {
		// shouldn't occur
	    }
    	    ztex.vendorCommand( 0x83, "Send hash data", m, 0 );
	}
        usbTime += new Date().getTime() - t;

        ignoreErrorTime = new Date().getTime() + 2000; // ignore errors for next 2s
    }

// ******* suspend *************************************************************
    public boolean suspend ( )  {
        suspended = true;
	if ( suspendSupported ) {
	    try {
		synchronized (ztex) {
		    selectFpga();
    		    ztex.vendorCommand( 0x84, "Suspend" );
    		}
	    }
	    catch ( Exception e )  {
		msg( "Suspend command failed: " + e.getLocalizedMessage() );
		return false;
	    }
	}
	else {
	    msg( "Suspend command not supported. Update Firmware." );
	    return false;
	}
	return true;
    }

// ******* updateFreq **********************************************************
    public void updateFreq() throws UsbException {

	for ( int i=0; i<freqMaxM; i++ )  {
	    if ( maxErrorRate[i+1]*i < maxErrorRate[i]*(i+20) )
		maxErrorRate[i+1] = maxErrorRate[i]*(1.0+20.0/i);
	}

	int maxM = 0;
	while ( maxM<freqMDefault && maxErrorRate[maxM+1]<maxMaxErrorRate )
	    maxM++;
	while ( maxM<freqMaxM && errorWeight[maxM]>150 && maxErrorRate[maxM+1]<maxMaxErrorRate )
	    maxM++;
	    
	int bestM=0;
	double bestR=0;
	for ( int i=0; i<=maxM; i++ )  {
	    double r = (i + 1 + ( i == freqM ? errorHysteresis : 0))*(1-maxErrorRate[i]);
	    if ( r > bestR ) {
		bestM = i;
		bestR = r;
	    }
	}
	
	if ( bestM != freqM ) {
	    msg ( "Set frequency " + ( freqM<0 ? "" : "from " + String.format("%.2f",(freqM+1)*(freqM1)) + "MHz ") + "to " + String.format("%.2f",(bestM+1)*(freqM1)) +"MHz" );
	    freqM = bestM;
	    setFreq( freqM );
	}

	maxM = freqMDefault;
	while ( maxM<freqMaxM && errorWeight[maxM+1]>100 )
	    maxM++;
	if ( ( bestM+1 < (1.0-overheatThreshold )*maxHashRate ) && bestM < maxM-1 )  {
	    try {
		synchronized (ztex) {
		    selectFpga();
		    ztex.resetFpga();
		}
	    }
	    catch ( Exception e ) {
	    }
	    throw new UsbException("Hash rate drop of " + String.format("%.1f",(1.0-1.0*(bestM+1)/maxHashRate)*100) + "% detect. This may be caused by overheating. FPGA is shut down to prevent damage." );
	}
	
	double temp;
	try { 
	    temp = ztex.tempSensorRead(fpgaNum);
	}	    
	catch ( Exception e ) {
	    temp = tempLimit - 1e12;
	}
	if ( temp > tempLimit ) {
	    try {
		synchronized (ztex) {
		    selectFpga();
		    ztex.resetFpga();
		}
	    }
	    catch ( Exception e ) {
	    }
	    throw new UsbException("Overheating detected: T=" + String.format("%.1f",temp) + "C. FPGA is shut down to prevent damage." );
	}

    }

// ******* getNonces ***********************************************************
    public boolean getNonces() throws UsbException, MalformedURLException, IOException {
	if ( !isRunning || disableTime[prevRpcNum] > new Date().getTime() ) return false;
	
	rpcNum = prevRpcNum;
	
	getNoncesInt();
	
        if ( ignoreErrorTime < new Date().getTime() ) {
	    errorCount[freqM] *= 0.995;
    	    errorWeight[freqM] = errorWeight[freqM]*0.995 + 1.0;
            for ( int i=0; i<numNonces; i++ ) {
        	if ( ! checkNonce( nonce[i], hash7[i] ) )
    		    errorCount[freqM] +=1.0/numNonces;
    	    }
    	    
	    errorRate[freqM] = errorCount[freqM] / errorWeight[freqM] * Math.min(1.0, errorWeight[freqM]*0.01) ;
    	    if ( errorRate[freqM] > maxErrorRate[freqM] )
    	        maxErrorRate[freqM] = errorRate[freqM];
    	    if ( errorWeight[freqM] > 120 )
    		maxHashRate = Math.max(maxHashRate, (freqM+1.0)*(1-errorRate[freqM]));
    	}
    	
	boolean submitted = false;
        for ( int i=0; i<numNonces*(1+extraSolutions); i++ ) {
    	    int n = goldenNonce[i];
    	    if ( n != -offsNonces ) {
    	    	int j=0;
    	        while ( j<lastGoldenNonces.length && lastGoldenNonces[j]!=n )
    	    	  j++;
        	if  (j>=lastGoldenNonces.length) {
        	     submitWork( n );
        	     submittedCount+=1;
        	     submitted = true;
        	}
    	    }
        }
        return submitted;
    } 

// ******* getNoncesInt ********************************************************
// it comes in the format {hash, nonce, golden_nonce}
// hash is hardwired to zero. We are getting the nonce and the golden nonce
    public void getNoncesInt() throws UsbException {
	int bs = 12+extraSolutions*4; //for the board 1.15b its is 0
	byte[] buf = new byte[numNonces*bs];
	boolean overflow = false;

	long t = new Date().getTime();
	synchronized (ztex) {
	    try {
		selectFpga();
	    }
	    catch ( InvalidFirmwareException e )  {
	    // shouldn't occur
	    }
    	    ztex.vendorRequest2( 0x81, "Read hash data", 0, 0, buf, numNonces*bs ); //it should be  12 bytes 
    	}
        usbTime += new Date().getTime() - t;
	//System.out.print("rd:" + dataToHexStr(buf)+ "  ");
        for ( int i=0; i<numNonces; i++ ) { //for 1.15b numNonces is always 1, so i is always 0
	    goldenNonce[i*(1+extraSolutions)] = dataToInt(buf,i*bs+0) - offsNonces;
	    int j = dataToInt(buf,i*bs+4) - offsNonces;
	    overflow |= ((j >> 4) & 0xfffffff) < ((nonce[i]>>4) & 0xfffffff);
	    nonce[i] = j;
	    hash7[i] = dataToInt(buf,i*bs+8); //it would store the hash received from FPGA in our case its always 000000
	    for ( j=0; j<extraSolutions; j++ )
		goldenNonce[i*(1+extraSolutions)+1+j] = dataToInt(buf,i*bs+12+j*4) - offsNonces;
	}
	if ( overflow && ! PollLoop.scanMode )
	    overflowCount += 1;
    }

// ******* checkNonce *******************************************************
    public boolean checkNonce( int n, int h ) throws UsbException {
        return false;
    }

// ******* totalHashRate *******************************************************
    public double totalHashRate () {
	return fatalError == null ? (freqM+1)*freqM1*(1-errorRate[freqM])*hashesPerClock : 0;
    }
    
// ******* submittedHashRate ***************************************************
    public double submittedHashRate () {
	return fatalError == null ? 4.294967296e6 * totalSubmittedCount / (new Date().getTime()-startTime) : 0;
    }
    
// ******* printInfo ***********************************************************
    public void printInfo( boolean force ) {
	long t = new Date().getTime();
	if ( !force && (clusterMode || lastInfoTime+infoInterval > t || !isRunning) )
	    return;
	    
	if ( fatalError != null ) {
	    printMsg2(name + ": " + fatalError);
	    return;
	}

	if ( suspended ) {
	    printMsg2(name + ": Suspended");
	    return;
	}
	
	StringBuffer sb = new StringBuffer( "f=" + String.format("%.2f",(freqM+1)*freqM1)+"MHz" );

	if ( errorWeight[freqM]>20 )
	    sb.append(",  errorRate="+ String.format("%.2f",errorRate[freqM]*100)+"%");

	if ( errorWeight[freqM]>100 )
	    sb.append(",  maxErrorRate="+ String.format("%.2f",maxErrorRate[freqM]*100)+"%");

	double hr = (freqM+1)*freqM1*(1-errorRate[freqM])*hashesPerClock;

	if ( errorWeight[freqM]>20 )
	    sb.append(",  hashRate=" + String.format("%.1f", hr )+"KH/s" );
	    
	try { 
	    sb.append(", T=" + String.format("%.1f",ztex.tempSensorRead(fpgaNum)) + "C");
	}	    
	catch ( Exception e ) {
	}
	    
	sb.append(",  submitted " +submittedCount+" new nonces,  luckFactor=" + String.format("%.2f", submittedHashRate()/hr+0.0049 ));
	submittedCount = 0;
	
	printMsg2(name + ": " + sb.toString());
	    
	lastInfoTime = t;
    }

// ******* getDescriptor *******************************************************
    private void getDescriptor () throws UsbException, FirmwareException {
	byte[] buf = new byte[64];

        ztex.vendorRequest2( 0x82, "Read descriptor", 0, 0, buf, 64 );
        if ( buf[0] != 5 ) {
    	    if ( ( buf[0] != 2 ) && ( buf[0] != 4 ) ) {
    		throw new FirmwareException("Invalid LTCMiner descriptor version. Firmware must be updated.");
    	    }
            msg("Warning: Firmware out of date");
    	}
        numNonces = (buf[1] & 255) + 1;
        offsNonces = ((buf[2] & 255) | ((buf[3] & 255) << 8)) - 10000;
        freqM1 = ( (buf[4] & 255) | ((buf[5] & 255) << 8) ) * 0.01;
        freqM = (buf[6] & 255);
        freqMaxM = (buf[7] & 255);
        if ( freqM > freqMaxM )
    	    freqM = freqMaxM;
        freqMDefault = freqM;
        
        suspendSupported = buf[0] == 5;
        
        hashesPerClock = buf[0] > 2 ? ( ( (buf[8] & 255) | ((buf[9] & 255) << 8) ) +1 )/128.0 : 1.0;
        extraSolutions = buf[0] > 4 ? buf[10] : 0;
        
        int i0 = buf[0] > 4 ? 11 : ( buf[0] == 4 ? 10 : 8 );
        int i = i0;
        while ( i<64 && buf[i]!=0 )
    	    i++;
    	if ( i < i0+1)
    	    throw new FirmwareException("Invalid bitstream file name");
    	bitFileName = new String(buf, i0, i-i0);

        if ( buf[0] < 4 ) {
    	    if ( bitFileName.substring(0,13).equals("ztex_ufm1_15b") ) 
    		hashesPerClock = 0.5;
    	    msg( "Warning: HASHES_PER_CLOCK not defined, assuming " + hashesPerClock );
    	}
    }
    
// ******* checkUpdate **********************************************************
    public boolean checkUpdate() {
	long t = new Date().getTime();
	if ( !isRunning ) return true;
	if ( ignoreErrorTime > t ) return false;
	if ( disableTime[prevRpcNum] > t ) return true;
	if ( lastGetWorkTime + maxPollInterval < t ) return true;
	for ( int i=0; i<numNonces ; i++ )
	    if ( ((nonce[i]>>1) & 0x7fffffff) > (0x38000000 + Math.round(Math.random()*0x10000000)) ) return true;
	return false;
    }

// ******* descriptorInfo ******************************************************
    public String descriptorInfo () {
	return "bitfile=" + bitFileName + "   f_default=" + String.format("%.2f",freqM1 * (freqMDefault+1)) + "MHz  f_max=" + String.format("%.2f",freqM1 * (freqMaxM+1))+ "MHz  HpC="+hashesPerClock+"H";
    }

// ******* resetCounters ******************************************************
    public void resetCounters () {
	while ( freqMDefault<freqM && errorWeight[freqMDefault+1]>100 )
	    freqMDefault++;

	for ( int i=0; i<255; i++ ) {
	    errorCount[i] *= 0.05;
	    errorWeight[i] *= 0.05;
	    errorRate[i]=0;
	    maxErrorRate[i]=0;
	}
	startTime = new Date().getTime();
	totalSubmittedCount = 0;
    }
    
// *****************************************************************************
// ******* main ****************************************************************
// *****************************************************************************
    public static void main (String args[]) {
    
	int devNum = -1;
	boolean workarounds = false;
	
        String firmwareFile = null, snString = null;
        boolean printBus = false;
        boolean verbose = false;
        boolean eraseFirmware = false;

        String filterType = null;
        String logFileName = "LTCMiner.log";
        
        char mode = 's';
        
        rpcCount = 1; 
        rpcurl[0] = "http://127.0.0.1:8332";
        rpcuser[0] = null;
        rpcpassw[0] = null;

	try {
// init USB stuff
	    LibusbJava.usb_init();

	    
// scan the command line arguments
    	    for (int i=0; i<args.length; i++ ) {
	       if ( args[i].equals("-host") ) {
	    	    i++;
		    try {
			if (i>=args.length) throw new Exception();
    			rpcurl[0] = args[i];
		    } 
		    catch (Exception e) {
		        throw new ParameterException("URL expected after -host");
		    }
		}
	        else if ( args[i].equals("-u") ) {
	    	    i++;
		    try {
			if (i>=args.length) throw new Exception();
    			rpcuser[0] = args[i];
		    } 
		    catch (Exception e) {
		        throw new ParameterException("User expected after -u");
		    }
		}
	        else if ( args[i].equals("-p") ) {
	    	    i++;
		    try {
			if (i>=args.length) throw new Exception();
    			rpcpassw[0] = args[i];
		    } 
		    catch (Exception e) {
		        throw new ParameterException("Password expected after -p");
		    }
		}
	        else if ( args[i].equals("-lp") ) {
	    	    i+=3;
		    try {
			if (i>=args.length) throw new Exception();
    			longPollURL = args[i-2];
    			longPollUser = args[i-1];
    			longPollPassw = args[i];
		    } 
		    catch (Exception e) {
		        throw new ParameterException("<URL> <user name> <password> expected after -lp");
		    }
		}
	       else if ( args[i].equals("-m") ) {
	    	    i++;
		    try {
			if (i>=args.length) throw new Exception();
			if ( args[i].length() < 1 ) throw new Exception();
			mode = Character.toLowerCase( args[i].charAt(0) );
			if ( mode != 's' && mode != 't'  && mode != 'p' && mode != 'c' ) throw new Exception();
		    } 
		    catch (Exception e) {
		        throw new ParameterException("s|t|p|c expected after -m");
		    }
		}
		else if ( args[i].equals("-i") ) {
		    printBus = true;
		} 
		else if ( args[i].equals("-v") ) {
		    verbose = true;
		} 
		else if ( args[i].equals("-h") ) {
		        System.err.println(ParameterException.helpMsg);
	    	        System.exit(0);
		}
	       else throw new ParameterException("Invalid Parameter: "+args[i]);
	    }
	    
	    logFile = new PrintStream ( new FileOutputStream ( logFileName, true ), true );
    
   
	    if ( mode != 't' && mode != 'p' ) {
		if ( rpcuser[0] == null ) {
		    System.out.print("Enter RPC user name: ");
		    rpcuser[0] = new BufferedReader(new InputStreamReader( System.in) ).readLine();
		}

		if ( rpcpassw[0] == null ) {
		    System.out.print("Enter RPC password: ");
		    rpcpassw[0] = new BufferedReader(new InputStreamReader(System.in) ).readLine();
		}
	    }
		
	    if ( mode == 's' || mode == 't' ) {
		if ( devNum < 0 )
		    devNum = 0;
	
		ZtexScanBus1 bus = new ZtexScanBus1( ZtexDevice1.ztexVendorId, ZtexDevice1.ztexProductId, filterSN==null, false, 1,  filterSN, 10, 0, 1, 0 );
		if ( bus.numberOfDevices() <= 0) {
		    System.err.println("No devices found");
		    System.exit(0);
		} 
		if ( printBus ) {
	    	    printBus(bus);
	    	    System.exit(0);
		}
		
	        LTCMiner miner = new LTCMiner ( bus.device(devNum), firmwareFile, verbose );
		if ( mode == 't' ) { // single mode
		//here lets add the scrypt test data from here:
		    //miner.initWork(chunkEndianSwitch(hexStrToData("000000014eb4577c82473a069ca0e95703254da62e94d1902ab6f0eae8b1e718565775af20c9ba6ced48fc9915ef01c54da2200090801b2d2afc406264d491c7dfc7b0b251e91f141b44717e00310000")));
		miner.initWork(hexStrToData("00001300e71744b141f19e152b0b7cfd7c194d462604cfa2d2b1080900022ad45c10fe5199cf84dec6ab9c02fa577565817e1b8eae0f6ba2091d49e26ad45230759e0ac960a37428c7754be410000000" )); //this data is already in little endian at nonce 0000318f

		    miner.sendData ( );
		    for (int i=0; i<Integer.MAX_VALUE; i++ ) {
			try {
			    Thread.sleep( 250 );
			}
			catch ( InterruptedException e) {
			}	 
			miner.getNoncesInt();

    			for ( int j=0; j<miner.numNonces; j++ ) {
	    		    System.out.println( i  + ":  miner nonce " + intToHexStr(miner.nonce[j]));  
	    		}
		    } 
		}
		else { // single mode
		    Vector<LTCMiner> v = new Vector<LTCMiner>();
		    v.add ( miner );
		    for ( int i=1; i<miner.numberOfFpgas(); i++ )
			v.add(new LTCMiner(miner.ztex(), miner.fpgaNum(i), verbose) );
		    System.out.println("");
		    if ( miner.ztex().numberOfFpgas()>1 ) 
			System.out.println("A multi-FPGA board is detected. Use the cluster mode for additional statistics.");
		    System.out.println("Disconnect device or press Ctrl-C for exit\n");
		    new PollLoop(v).run(); 
		}
	    }
    
	}
	catch (Exception e) {
	    System.out.println("Error:2 "+e.getLocalizedMessage() );
	} 


	System.exit(0);
	
   } 

}
