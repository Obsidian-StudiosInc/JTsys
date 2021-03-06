/*
 * Copyright 2017 Obsidian-Studios, Inc.
 * Distributed under the terms of the GNU General Public License v3
 *
 */

package jtsys;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 *
 * @author William L. Thomson Jr. <wlt@o-sinc.com>
 * Based on VirtualNet.pm by Ivan Kohler <ivan-virtualnet@420.am>
 */
public class Tsys {

    private final char STX = 0x02;//0b1100100
    private final char ETX = 0x03;//0b0000011
    private final char FS  = 0x1c;//0b0011100
    private final char GS  = 0x1d;//0b
    private final char ETB = 0x17;//0b0010111

    // A/N Device Codes
    private final char[] DEVICE_CODES = {                   // Device Code (4.62)
        '0',                                                // 0="Unkown or Unsure"
        'C',                                                // C="P.C." 
        'D',                                                // D="Dial Terminal"
        'E',                                                // E="Electronic Cash Register"
        'I',                                                // I="In-Store Processor"
        'M',                                                // M="Main Frame"
        'P',                                                // P="Reserved POS-Port®"
        'Q',                                                // Q="Reserved Third party software developer"
        'R',                                                // R="POS-Port®"
        'S',                                                // S="POS Partner®"
        'Z'                                                 // Z="Suppress PS2000/Merit response fields"
    };
    private final short[] COUNTRY_CODES = { 840 };   // 840=US
    private final short[] CURRENCY_CODES = { 840 };  // 840=USD
    private final short[] TIME_ZONES = {
        705,                                                // 705=EST
        706,                                                // 706=CST
        707,                                                // 707=MST
        708,                                                // 708=PST
    };
    private final String[] ERROR_RESPONSE_KEYS = {
        "Code",
        "Text"
    };
    private final String[] MIME = {
        "x-Visa-II/x-auth",                                 // Auth mime
        "x-Visa-II/x-settle"                                // Settle mime
    };
    private final String[] LANGUAGES = { "00" };      // 00=English
 
    private final static String TSYS_URL = "https://ssl1.tsysacquiring.net/scripts/gateway.dll?transact";

    private boolean debug = false;

    /**
     * Empty constructor required to have one without parameters
     */
    public Tsys() {}

    /**
     * Constructor to set debugging
     * @param debug boolean to enable debugging, true to enable, default false
     */
    public Tsys(boolean debug) {
        this.debug = debug;
    }

    private HttpsURLConnection getHttpsConnection() throws IOException {
        URL url = new URL(TSYS_URL);
        HttpsURLConnection httpsCon = (HttpsURLConnection)url.openConnection();
        httpsCon.setRequestMethod("POST");
        httpsCon.setDoOutput(true);
        httpsCon.setDoInput(true);
        httpsCon.setUseCaches(false);
        return(httpsCon);
    }

    public String separator(String obj,
                            String s,
                            int length,
                            char etbx) throws Exception {
        if(s.length()!=length)
            throw new Exception(obj+" length is "+s.length()+" and should be "+length);
        return (STX+s+etbx+lrc(s+etbx));
    }

    private LinkedHashMap<String,String> submit(String request,
                                                String mime) throws IOException,
                                                                    Exception {
        LinkedHashMap<String,String> map = null;
        HttpsURLConnection httpsCon = getHttpsConnection();
        httpsCon.setRequestProperty("Content-Type", mime);
        httpsCon.setRequestProperty("Content-Length", String.valueOf(request.length()));
        DataOutputStream wr = new DataOutputStream(httpsCon.getOutputStream());
        wr.write(getEvenParity(request));
        wr.flush();
        String cipher = httpsCon.getCipherSuite();
        InputStream is = httpsCon.getInputStream();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1)
            result.write(buffer, 0, length);
        is.close();
        httpsCon.disconnect();
        map = new LinkedHashMap<>();
        String response = new String(removeParity(result.toByteArray()));
        String error_pattern = "^(\\d+)\\s+\\-\\s+(\\S.*)$";            // Error Pattern
        Matcher auth = Pattern.compile(authResponseRexEx()).matcher(response);
        Matcher settle = Pattern.compile(settleResponseRexEx()).matcher(response);
        Matcher duplicate = Pattern.compile(settleResponseDupRexEx()).matcher(response);
        Matcher reject = Pattern.compile(settleResponseRejectRexEx()).matcher(response);
        Matcher error = Pattern.compile(error_pattern).matcher(response);
        if(auth.matches()) {
            AuthResponseKeys[] values = AuthResponseKeys.values();
            int g = 0;
            for(int i=0;i<values.length;i++) {
                g++; // Separate iterator to skip AVS Text :/
                map.put(values[i].key(),auth.group(g).trim());
                if(auth.group(g).length()==1 &&
                   values[i].name().equals(AuthResponseKeys.AVS_Result_Code.name())) {
                    i++; // increment values to skip AVS_Result_Text
                    for(AVSCodes c: AVSCodes.values())
                        if(c.name().equals(auth.group(g)))
                            map.put(values[i].key(),c.value());
                        }
                }
        } else if(settle.matches()) {
            SettleResponseKeys[] values = SettleResponseKeys.values();
            for(int i=0;i<values.length;i++)
                map.put(values[i].key(),settle.group(i+1).trim());
        } else if(duplicate.matches()) {
            SettleResponseKeys[] values = SettleResponseKeys.values();
            for(int i=0;i<values.length-1;i++)
                map.put(values[i].key(),duplicate.group(i+1).trim());
            map.put("Batch Date",duplicate.group(values.length));
        } else if(reject.matches()) {
            SettleResponseErrorKeys[] values = SettleResponseErrorKeys.values();
            for(int i=0;i<values.length;i++) {
                String name = values[i].name().replace("_"," ");
                if(reject.group(i+1).length()==1)
                    if(values[i].name().equals(SettleResponseErrorKeys.Error_Type.name()))
                        for(SettleErrorTypes t: SettleErrorTypes.values())
                            if(t.name().equals(reject.group(i+1)))
                                map.put(name,t.value());
                    else if(values[i].name().equals(SettleResponseErrorKeys.Error_Record_Type.name()))
                        for(SettleErrorRecordTypes r: SettleErrorRecordTypes.values())
                            if(r.name().equals(reject.group(i+1)))
                                map.put(name,r.value());
                else
                    map.put(name,reject.group(i+1).trim());
            }
        } else if(error.matches())
            for(int i=0;i<ERROR_RESPONSE_KEYS.length;i++)
                map.put(ERROR_RESPONSE_KEYS[i],error.group(i+1));
        else
            Logger.getLogger(Tsys.class.getName()).log(Level.SEVERE,
                    String.format("\nUn-matched response : %s \n\n",response));
        if(debug)
            Logger.getLogger(Tsys.class.getName()).log(Level.SEVERE,
                String.format("Cipher       : %s\n"
                            + "IP           : %s\n"
                            + "Request      : %s\n"
                            + "Response     : %s\n\n",
                              cipher,
                              InetAddress.getByName(httpsCon.getURL().getHost()).getHostAddress(),
                              request,
                              response));
        return(map);
    }

    /**
     * Authorize a credit card
     *
     * @param merchant Merchant account to use
     * @param transSequenceNumber 
     * @param cardNumber Credit card number
     * @param expiration Credit card expiration
     * @param address Credit card holder address
     * @param zip Credit card holder zip code
     * @param amount Amount of charge to be authorized
     * @return LinkedHashMap<String,String> containing credit card
     *                                      authorization or error response
     *                                      if length is 2, there was an error
     *                                      otherwise use AuthResponseKeys enum
     *                                      for key names to access values
     * @throws Exception if any errors occur, request not proper length, issue
     *                   with connection, etc.
     */
    public LinkedHashMap<String,String> auth(Merchant merchant,
                                             String transSequenceNumber,
                                             String cardNumber,
                                             String expiration,
                                             String address,
                                             String zip,
                                             String amount) throws Exception {
        String r = authRequest(merchant,
                               transSequenceNumber,
                               cardNumber,
                               expiration,
                               address,
                               zip,
                               amount);
        return(submit(r,MIME[0]));
    }
    
    private String authRequest(Merchant merchant,
                               String transSequenceNumber,
                               String cardNumber,
                               String expiration,
                               String address,
                               String zip,
                               String amount) throws Exception {
        //Byte Length Field: Content
        StringBuilder c = new StringBuilder("D4.");         // 1     1    Record format: D
                                                            // 2     1    Application Type: 4=Interleaved
                                                            // 3     1    Message Delimiter: .
        c.append(merchant.getBin());                        // 4-9   6    Acquirer BIN
        c.append(merchant.getId());                         // 10-21 12   Merchant Number
        c.append(merchant.getStore());                      // 22-25 4    Store Number
        c.append(merchant.getTerminal());                   // 26-29 4    Terminal Number
        c.append(DEVICE_CODES[7]);                          // 30    1    Device Code 1 PC or 7 Third Party
        c.append(merchant.getIndustryCode());               // 31    1    Industry Code
        c.append(CURRENCY_CODES[0]);                        // 32-34 3    Currency Code: 840=U.S. Dollars
        c.append(COUNTRY_CODES[0]);                         // 35-37 3    Country Code: 840=United States
        c.append(String.format("%-9.9s",merchant.getZip())); // 38-46 9    (Merchant) City Code(Zip);
        c.append(LANGUAGES[0]);                             // 47-48 2    Language Indicator: 00=English
        c.append(TIME_ZONES[0]);                            // 49-51 3    Time Zone Differential: 705=EST
        c.append(merchant.getMcc());                        // 52-55 4    Metchant Category Code
        c.append('Y');                                      // 56    1    Requested ACI (Authorization Characteristics Indicator):
                                                            //            Y=Device is CPS capable
        c.append(transSequenceNumber);                      // 57-60 4    Tran Sequence Number
        c.append("56");                                     // 61-62 2    Auth Transaction Code: 56=Card Not Present
        c.append('N');                                      // 63    1    Cardholder ID Code: N=AVS
                                                            //            (Address Verification Data or
                                                            //             CPS/Card Not Present or
                                                            //             Electronic Commerce)
        c.append('@');                                      // 64    1    Account Data Source: @=No Cardreader

        if(c.length()!=64)
            throw new Exception("Content length is "+c.length()+" and should be 64\n"+c.toString());

        c.append(cardNumber).append(FS);                    // - 5-76  Customer Data Field: Acct#<FS>
        c.append(expiration).append(FS);                    //                              ExpDate<FS>
        c.append(FS);                                       // - 1 Field Separator
        address = address.replaceAll("[^a-zA-Z0-9]","");
        if(address.length()+zip.length()>28)
            address = address.substring(0,28-zip.length());
        c.append(address).append(' ').append(zip);          // - 0-29 Address Verification Data
        c.append(FS).append(FS);                            // - 2 Field Separator
        //String.format("%12s",amount.replace(".","")).replace(" ","0")
        c.append(amount.replace(".",""));                   // - 1-12 Transaction Amount
        c.append(FS).append(FS).append(FS);                 // - 3 Field Separator
        c.append(String.format("%-25.15s",merchant.getName())); // - 25 Merchant Name Left-Justified/Space-Filled
        c.append(String.format("%-13.13s",merchant.getCity())); // - 13 Customer Service Phone Number NNN-NNNNNNN (dash is required)
//        c.append(merchant.getPhone());                      // - 13 Customer Service Phone Number NNN-NNNNNNN (dash is required)
        c.append(String.format("%-2.2S",merchant.getState())); // - 2 Merchant State
        c.append(FS).append(FS).append(FS);                 // - 3 Field Separator
//        c.append("007");                                    // - 3 Group III Version Number: 014=MOTO/Electronic Commerce
//        String cvv2 = "";
                                                            // - 6 VISA CVV2, Mastercard CVC2, AMEX CID
                                                            // Position - Value Description
                                                            // 1 - 0 Card Verification Value is intentionally not provided
                                                            // 1 - 1 Card Verification Value is Present
        
                                                            // 2 - 0 Only the normal Response Code should be returned
                                                            // 2 - 1 Response Code and the CVV2 / CVC2 Result Code should be returned
                                                            // 3-6 - Card Verification Value as printed on card (right-justify/space-fill entry)
                                                            // If position 1 = 0, 2, or 9, positions 3-6 should be space-filled.
//        if(cvv2!=null &&
//           !cvv2.isEmpty() &&
//           !cvv2.startsWith("0") &&
//           !cvv2.startsWith("2") &&
//           !cvv2.startsWith("9"))
//            c.append("11").append(String.format("%4s",cvv2)); // CVV2 Present
//        else
//            c.append("00    ");                             // - CVV2 Not Present 
//        c.append(GS);                                       // - 1 Group Separator
        c.append("014");                                    // - 3 Group III Version Number: 014=MOTO/Electronic Commerce
        c.append('7');                                      // - 1 MOTO/Electronic Com. Ind: 7= Non-Authenticated
//        c.append(GS); 
//        c.append("020");                                    // - 3 Group III Version Number: 014=MOTO/Electronic Commerce
//        c.append("001131B002");                             // - 1 MOTO/Electronic Com. Ind: 7= Non-Authenticated
                                                            //     Security transaction, such as a channel-encrypted
                                                            //     transaction (e.g., ssl, DES or RSA)
//        c.append(GS);                            // - 2 Field Separator
        return(STX+c.toString()+ETX+lrc(c.toString()+ETX));
    }

    /**
     * Auth (1080) Response RegEx
     * D-Format Credit Card Authorization Response Message RegEx
     *
     * Group 1.  1    A/N Returned ACI 4.73
     * Group 2.  13   A/N Authorization Source Code 4.12
     * Group 3.  2    A/N Response Code XX  4.71
     * Group 4.  6    A/N Approval Code 4.8
     * Group 5.  16   A/N Auth Response Text  4.11
     * Group 6.  1    A/N AVS Result Code 4.3
     * Group 7.  12   A/N Retrieval Reference Num 4.72
     * Group 8.  0-15 A/N Transaction Identifier 4.91
     * Group 9.  0-4  A/N Validation Code 4.96
     * Group 10. 3    NUM Group III Version Number 4.44
     *
     * @return String containing regex pattern to parse auth response regex
     */
    private String authResponseRexEx() {
        // D-Format Credit Card Authorization Response Message
        StringBuilder r = new StringBuilder();
                                                // Byte Length Format Field Description Content Section
        r.append(STX);
        r.append('E');                          // 1     1    Record Format E 4.68
        r.append("[024]");                      // 2     1    NUM Application Type 0=Single Transaction 4.7
                                                //                                2=Multiple Transaction
                                                //                                4=Interleaved
        r.append("\\.");                        // 3     1    Message Delimiter 4.63
        r.append("([A-Z ])");                   // 4     1    Returned ACI 4.73
        r.append("[0-9 ]{4}");                  // 5-8   4    NUM Store Number 4.82
        r.append("[0-9 ]{4}");                  // 9-12  4    NUM Terminal Number 4.85
        r.append("(.)");                        // 13    1    Authorization Source Code 4.12
        r.append("[0-9 ]{4}");                  // 14-17 4    NUM Transaction Sequence Num 4.92
        r.append("([0-9]{2})");                 // 18-19 2    Response Code XX  4.71
        r.append("([0-9A-Za-z ]{6})");          // 20-25 6    Approval Code 4.8
        r.append("[0-9]{6}");                   // 26-31 6    NUM Local Transaction Date MMDDYY 4.55
        r.append("[0-9]{6}");                   // 32-37 6    NUM Local Transaction Time HHMMSS 4.56
        r.append("([0-9A-Za-z ]{16})");         // 38-53 16   Auth Response Text  4.11
        r.append("([0-9A-Z ])");                // 54    1    AVS Result Code 4.3
        r.append("([0-9A-Za-z ]{12})");         // 55-66 12   Retrieval Reference Num 4.72
        r.append("[0-9A-Za-z ]");               // 67    1    Market Data Identifier 4.57
        r.append("([0-9A-Za-z ]{0,15})");       // -     0-15 Transaction Identifier 4.91
        r.append(FS);                           // -     1    Field Separator <FS>  4.41
        r.append("([0-9A-Za-z ]{0,4})");        // -     0-4  Validation Code 4.96
        r.append(FS);                           // -     1    Field Separator <FS> 4.41
        r.append("([0-9]{3})?");                // -     3    NUM Group III Version Number 4.44
        r.append("(.*)");
        return(r.toString());
    }

    /**
     * Settle a credit card authorization (single card batch)
     *
     * Only supports single card settlements not batch settlements
     *
     * @param merchant Merchant account to use
     * @param cardNumber Credit card number
     * @param transSequenceNumber Transaction Sequence Num
     * @param batchNumber Merchant specific batch number
     * @param aci Returned ACI 4.73
     * @param authSourceCode Authorization Source Code 4.12
     * @param responseCode Response Code
     * @param authCode Approval Code
     * @param avsCode AVS Result Code 4.3
     * @param transId Transaction Identifier 4.91
     * @param validationCode Validation Code 4.96
     * @param amount Amount of charge to be authorized
     * @param purchaseId Invoice number
     * @param voidTrans boolean indicator to void transaction, true for void
     * @return LinkedHashMap<String,String> containing batch response status
     *                                      or error response if length is 2,
     *                                      there was an error otherwise use 
     *                                      AuthSettleKeys enum for key names
     *                                      to access values
     * @throws Exception if any errors occur, request not proper length, issue
     *                   with connection, etc.
     */
    public LinkedHashMap<String,String> settle(Merchant merchant,
                                               String cardNumber,
                                               String transSequenceNumber,
                                               String batchNumber,
                                               String aci,
                                               String authSourceCode,
                                               String responseCode,
                                               String authCode,
                                               String avsCode,
                                               String transId,
                                               String validationCode,
                                               String amount,
                                               String purchaseId,
                                               boolean voidTrans)  
                                                        throws Exception {
    String r = settleRequest(merchant,
                             cardNumber,
                             transSequenceNumber,
                             batchNumber,
                             aci,
                             authSourceCode,
                             responseCode,
                             authCode,
                             avsCode,
                             transId,
                             validationCode,
                             amount,
                             purchaseId,
                             voidTrans);
        return(submit(r,MIME[1]));
    }

    /**
     * Settle request contents
     * Actual K-Format 1081 settle request contents to be transmitted
     * via a connection
     *
     * @param merchant Merchant account to use
     * @param cardNumber Credit card number
     * @param transSequenceNumber Transaction Sequence Num
     * @param batchNumber Merchant specific batch number
     * @param aci Returned ACI 4.73
     * @param authSourceCode Authorization Source Code 4.12
     * @param responseCode Response Code
     * @param authCode Approval Code
     * @param avsCode AVS Result Code 4.3
     * @param transId Transaction Identifier 4.91
     * @param validationCode Validation Code 4.96
     * @param amount Amount of charge to be authorized
     * @param purchaseId Invoice number
     * @param voidTrans boolean indicator to void transaction, true for void
     * @return String containing a K-Format 1081 settle request 
     * @throws Exception if any errors occur, request not proper length, issue
     *                   with connection, etc.
     */
    private String settleRequest(Merchant merchant,
                                 String cardNumber,
                                 String transSequenceNumber,
                                 String batchNumber,
                                 String aci,
                                 String authSourceCode,
                                 String responseCode,
                                 String authCode,
                                 String avsCode,
                                 String transId,
                                 String validationCode,
                                 String amount,
                                 String purchaseId,
                                 boolean voidTrans) throws Exception {
        /* K-Format Header Record (Base Group)
         * Byte Length Frmt Field description Content Section
         * Byte Length Field: Content (section)
         */
        StringBuilder h = new StringBuilder("K1.ZH@@@@");   // 1     1  A/N Record Format: K (4.154)
                                                            // 2     1  NUM Application Type: 1=Single Batch (4.10)
                                                            // 3     1  A/N Message Delimiter: . (4.123)
                                                            // 4     1  A/N X.25 Routing ID: Z (4.226)
                                                            // 5-9   5  A/N Record Type: H@@@@ (4.155)
        h.append(merchant.getBin());                        // 10-15 6  NUM Acquirer BIN  (4.2)
        h.append(merchant.getAgent());                      // 16-21 6  NUM Agent Bank Number (4.5)
        h.append(merchant.getChain());                      // 22-27 6  NUM Agent Chain Number (4.6)
        h.append(merchant.getId());                         // 28-39 12 NUM Merchant Number (4.121)
        h.append(merchant.getStore());                      // 40-43 4  NUM Store Number (4.187)
        h.append(merchant.getTerminal());                   // 44-47 4  NUM Terminal Number 9911 (4.195)
        h.append(DEVICE_CODES[0]);                          // 48    1  A/N Device Code: Q="Third party software developer" (4.62)
        h.append(merchant.getIndustryCode());               // 49    1  A/N Industry Code (4.94)
        h.append(CURRENCY_CODES[0]);                        // 50-52 3  NUM Currency Code (4.52)
        h.append(LANGUAGES[0]);                             // 53-54 2  NUM Language Indicator: 00=English (4.104)
        h.append(TIME_ZONES[0]);                            // 55-57 3  NUM Time Zone Differential (4.200)
        Date date = new Date();
        h.append(new SimpleDateFormat("MMdd").format(date)); // 58-61 4  NUM Batch Transmission Date MMDD (4.22)
        String my_batchNumber = String.format("%3.3s",batchNumber).replace(" ","0");
        h.append(my_batchNumber);                              // 62-64 3  NUM Batch Number 001 - 999 (4.18)
        h.append('0');                                      // 65    1  NUM Blocking Indicator 0=Not Blocked (4.23)

        StringBuilder msg = new StringBuilder();
        msg.append(separator("Header",h.toString(),65,ETB));

        // K-Format Parameter Record (Base Group)
        // Byte Length Frmt Field Description Content Section
        StringBuilder p = new StringBuilder("K1.ZP@@@@");   // 1   1 A/N Record Format: K (4.154)
                                                            // 2   1 NUM Application Type: 1=Single Batch (4.10)
                                                            // 3   1 A/N Message Delimiter: . (4.123)
                                                            // 4   1 A/N X.25 Routing ID: Z (4.226)
                                                            // 5-9 5 A/N Record Type: P@@@@ (4.155)
        p.append("840");                                    // 10-12 3 NUM Country Code 840 (4.47)
        p.append(String.format("%-9.9s",merchant.getZip())); // 13-21 9 A/N City Code Left-Justified/Space-Filled (4.43)
        p.append(merchant.getMcc());                        // 22-25 4 NUM Merchant Category Code (4.116)
        p.append(String.format("%-25.25S",merchant.getName())); // 26-50 25 A/N Merchant Name Left-Justified/Space-Filled (4.27.1)
        p.append(String.format("%-13.13S",merchant.getCity())); // 51-63 13 A/N Merchant City Left-Justified/Space-Filled (4.27.2)
        p.append(String.format("%-2.2S",merchant.getState())); // 64-65 2 A/N Merchant State (4.27.3)
        p.append("00001");                                  // 66-70 5 A/N Merchant Location Number 00001 (4.120)
        p.append(merchant.getV());                          // 71-78 8 NUM V Number 00000001 (4.194)

        msg.append(separator("Parameters",p.toString(),78,ETB));

        /* K-Format Detail Record (Electronic Commerce)
         * Byte Size Frmt Field Description Content Section
         * D@@'D'  `
         */
        StringBuilder d = new StringBuilder("K1.ZD@@`D");   // 1   1 A/N Record Format: K (4.154)
                                                            // 2   1 NUM Application Type 1=Single Batch (4.10)
                                                            // 3   1 A/N Message Delimiter: . (4.123)
                                                            // 4   1 A/N X.25 Routing ID: Z (4.226)
                                                            // 5-9 5 A/N Record Type: D@@`D (4.155)
        d.append("56");                                     // 10-11 2 A/N Transaction Code: 56 = Card Not Present (4.205)
        d.append('N');                                      // 12  1 A/N Cardholder Identification Code N (4.32)
                                                            //       (Address Verification Data or
                                                            //        CPS/Card Not Present or
                                                            //        Electronic Commerce)
        d.append('@');                                      // 13  1 A/N Account Data Source Code @ = No Cardreader (4.1)
        d.append(String.format("%-22.22s",cardNumber));     // 14-35 22 A/N Cardholder Account Number Left-Justified/Space-Filled (4.30)
        d.append('Y');                                      // 36  1 Requested ACI (Authorization Characteristics Indicator): N (4.163)
        if(aci==null || aci.isEmpty())
            aci = " ";
        d.append(aci);                                      // 37  1 A/N Returned ACI (4.168)
        if(authSourceCode==null || authSourceCode.isEmpty())
            authSourceCode = "6";
        d.append(authSourceCode);                           // 38 1 A/N Authorization Source Code (4.13)
        if(transSequenceNumber==null || transSequenceNumber.isEmpty())
            throw new Exception("Transaction Sequence Number missing");
        d.append(String.format("%4.4s",transSequenceNumber).replace(" ","0")); // 39-42 4 NUM Transaction Sequence Number Right-Justified/Zero-Filled (4.207)
        d.append(responseCode);                             // 43-44 2 A/N Response Code (4.164)
        d.append(String.format("%-6.6s",authCode));         // 45-50 6 A/N Authorization Code Left-Justified/Space-Filled (4.12)
        d.append(new SimpleDateFormat("MMdd").format(date)); // 51-54 4 NUM Local Transaction Date MMDD (4.113)
        d.append(new SimpleDateFormat("hhmmss").format(date)); // 55-60 6 NUM Local Transaction Time HHMMSS (4.114)
        // From auth
        if(avsCode==null || avsCode.isEmpty())
            throw new Exception("Address Verification Result Code missing");
        d.append(avsCode);                           // 61  1 A/N AVS Result Code (4.3)
        if(transId==null || transId.isEmpty())
            transId = "000000000000000";
        d.append(String.format("%-15.15s",transId));           // 62-76 15 A/N Transaction Identifier Left-Justified/Space-Filled (4.206)
        if(validationCode==null || validationCode.isEmpty())
            validationCode = "    ";
        d.append(validationCode);                           // 77-80 4 A/N Validation Code (4.218)
        if(voidTrans)
            d.append('V');                                  // 81   1 A/N Void Indicator <SPACE> = Not Voided (4.224)
        else
            d.append(' ');                                  // 81   1 A/N Void Indicator <SPACE> = Not Voided (4.224)
        d.append("00");                                     // 82-83 2 NUM Transaction Status Code 00 (4.208)
        d.append('0');                                      // 84   1 A/N Reimbursement Attribute 0 (4.157)
        
        String my_amount = String.format("%12.12s",amount.replace(".","")).replace(" ","0");
        d.append(my_amount);                                // 85-96 12 NUM Settlement Amount Right-Justified/Zero-Filled (4.175)
        d.append(my_amount);                                // 97-108 12 NUM Authorized Amount Right-Justified/Zero-Filled (4.14)
        d.append(my_amount);                                // 109-120 12 NUM Total Authorized Amount Right-Justified/Zero-Filled (4.201)
        // d.append('1');
        d.append('0');                                      // 121   1 A/N Purchase Identifier Format Code 1 (4.150)
        d.append(String.format("%-25.25s",purchaseId));        // 122-146 25 A/N Purchase Identifier Left-Justified/Space-Filled (4.149)
// ???
        d.append("01");                                     // 147-148 2 NUM Multiple Clearing Sequence Number (4.129)
// ???
        d.append("01");                                     // 149-150 2 NUM Multiple Clearing Sequence Count (1.128)
        d.append('7');                                      // 151 1 A/N MOTO/Electronic Commerce Indicator 7 = Channel Encrypted (4.127)

        msg.append(separator("Detail",d.toString(),151,ETB));

        // K-Format Trailer Record
        // Byte Length Frmt Field Description Content Section
        StringBuilder t = new StringBuilder("K1.ZT@@@@");   // 1    1 A/N Record Format K (4.154)
                                                            // 2    1 NUM Application Type 1=Single 3=Multiple Batch (4.10)
                                                            // 3    1 A/N Message Delimiter . (4.123)
                                                            // 4    1 A/N X.25 Routing ID Z (4.226)
                                                            // 5-9  5 A/N Record Type T@@@@ (4.155)
        t.append(new SimpleDateFormat("MMdd").format(date)); // 10-13  4 NUM Batch Transmission Date MMDD (4.22)
        t.append(my_batchNumber);                           // 14-16  3 NUM Batch Number 001 - 999 (4.18)
        t.append(String.format("%9.9s","4").replace(" ","0")); // 17-25  9 NUM Batch Record Count Right-Justified/Zero-Filled (4.19)
        my_amount = String.format("%16.16s",my_amount).replace(" ","0");
        t.append(my_amount);                                // 26-41 16 NUM Batch Hashing Total Purchases + Returns (4.16)
        t.append("0000000000000000");                       // 42-57 16 NUM Cashback Total (4.38)
        t.append(my_amount);                                // 58-73 16 NUM Batch Net Deposit Purchases - Returns (4.17)

        msg.append(separator("Trailer",t.toString(),73,ETX));

        return(msg.toString());
    }

    /**
     * Settle (1081) Response RegEx
     * K-Format Trailer Common Response Record RegEx
     *
     * Group 1.  9    Batch Record Count Right-Justified/Zero-Filled 4.19
     * Group 2.  16   Batch Net Deposit Right-Justified/Zero-Filled 4.17
     *
     * @return String containing regex pattern to parse settle response regex
     */
    private String settleResponseRexExCommon(String code) {
        StringBuilder r = new StringBuilder();
        r.append(STX);
        r.append('K');                          // 1     1    Record Format K 4.154
        r.append("[1,3]");                      // 2     1    Application Type 1=Single 3=Multiple Batch 4.10
        r.append("\\.");                        // 3     1    Message Delimiter 4.123
        r.append("Z");                          // 4     1    X.25 Routing ID Z 4.226
        r.append("R@@@@");                      // 5-9   5    Record Type R@@@@ 4.155
        r.append("([0-9]{9})");                 // 10-18 9    Batch Record Count Right-Justified/Zero-Filled 4.19
        r.append("([0-9]{16})");                // 19-34 16   Batch Net Deposit Right-Justified/Zero-Filled 4.17
        r.append(code);                         // 35-36 2    Batch Response Code ( GB, QD, RB ) 4.20
        r.append("00");                         // 37-38 2    Filler 00 4.79
        r.append("([0-9]{3})");                 // 39-41 3    Batch Number 999 4.18
        return(r.toString());
    }

    /**
     * Settle (1081) Response RegEx
     * K-Format Trailer “GB” (Good Batch)  Response Record RegEx
     *
     * Group 1.  9    Batch Record Count Right-Justified/Zero-Filled 4.19
     * Group 2.  16   Batch Net Deposit Right-Justified/Zero-Filled 4.17
     * Group 3.  2    Batch Response Code GB 4.20
     * Group 4.  3    Batch Number 999 4.18
     * Group 5.  9    Batch Response Text _ACCEPTED 4.21
     *
     * @return String containing regex pattern to parse settle response regex
     */
    private String settleResponseRexEx() {
        // K-Format Trailer “GB” Response Record
        StringBuilder r = new StringBuilder();
        r.append(settleResponseRexExCommon("GB"));
        r.append("([ _A-Z0-9]{9})");            // 42-50 9    Batch Response Text _ACCEPTED 4.21
        r.append("[ 0-9]{16}");                 // 51-66 16   Filler Spaces 4.78
        r.append("(.*)");
        return(r.toString());
    }

    /**
     * Settle (1081) Response Duplicate Batch RegEx
     * K-Format Trailer "QD" (Duplicate Batch) Response Record RegEx
     *
     * Group 1.  9    Batch Record Count Right-Justified/Zero-Filled 4.19
     * Group 2.  16   Batch Net Deposit Right-Justified/Zero-Filled 4.17
     * Group 3.  2    Batch Response Code RB 4.20
     * Group 4.  3    Batch Number 999 4.18
     * Group 5.  4    Batch Transmission Date MMDD 4.22
     *
     * @return String containing regex pattern to parse settle response regex
     */
    private String settleResponseDupRexEx() {
        // K-Format Trailer “QD” Response Record
        StringBuilder r = new StringBuilder();
        r.append(settleResponseRexExCommon("QD"));
        r.append("([0-9]{4})");                 // 42-45 4    Batch Transmission Date MMDD 4.22
        r.append("[ ]{21}");                    // 46-66 21   Filler Spaces 4.79
        r.append(".*");
        return(r.toString());
    }

    /**
     * Settle (1081) Response Reject Batch RegEx
     * K-Format Trailer "RB" (Rejected Batch) Response Record RegEx
     *
     * Group 1.  9    Batch Record Count Right-Justified/Zero-Filled 4.19
     * Group 2.  16   Batch Net Deposit Right-Justified/Zero-Filled 4.17
     * Group 3.  2    Batch Response Code RB 4.20
     * Group 4.  3    Batch Number 999 4.18
     * Group 5.  1    Error Type 4.71
     * Group 6.  4    Error Record Sequence Number 4.69
     * Group 7.  1    Error Record Type 4.70
     * Group 8.  2    Error Data Field Number 4.68
     * Group 9.  32   Error Data 4.67
     *
     * @return String containing regex pattern to parse settle response regex
     */
    private String settleResponseRejectRexEx() {
        // K-Format Trailer “RB” Response Record
        StringBuilder r = new StringBuilder();
        r.append(settleResponseRexExCommon("RB"));
        r.append("([A-Z])");                    // 42    1    Error Type 4.71
        r.append("([0-9]{4})");                 // 43-46 4    Error Record Sequence Number 4.69
        r.append("([A-Z])");                    // 47    1    Error Record Type 4.70
        r.append("([0-9]{2})");                 // 48-49 2    Error Data Field Number 4.68
        r.append("(.*)");
        return(r.toString());
    }

    /**
     * Generate Longitudinal Redundancy Check (LRC)
     *
     * @param s String to generate LRC
     * @return char representing the LRC
     */
    private char lrc(String s) {
        char lrc = 0;
        char[] chars = s.toCharArray();
        for (char c:chars)
            lrc ^= c;
        return(lrc);
    }

    /**
     * Get even parity, returns string as a byte array with even parity
     *
     * @param s string to be converted
     * @return byte[] with even parity bytes
     */
    public static byte[] getEvenParity(String s) {
        byte[] a = s.getBytes(StandardCharsets.US_ASCII);
        byte[] b = new byte[a.length];
        for (int i = 0; i < a.length; i++)
            b[i] = setEvenParity(a[i]);
        return b;
    }

    /**
     * Set even parity bit
     *
     * @param b byte to set even parity
     * @return byte with even parity
     */
    public static byte setEvenParity(byte b) {
        short c = 0;
        if (0 != (b & 0x01)) c++;
        if (0 != (b & 0x02)) c++;
        if (0 != (b & 0x04)) c++;
        if (0 != (b & 0x08)) c++;
        if (0 != (b & 0x10)) c++;
        if (0 != (b & 0x20)) c++;
        if (0 != (b & 0x40)) c++;
        return (1 == (c % 2)) ? (byte)(b | 0x80) : b;
    }

    /**
     * Remove parity bit, presently only 8th bit set to 0 if 1
     *
     * @param a byte[] with parity bit set
     * @return byte[] with 8th parity it removed
     */
    public static byte[] removeParity(byte[] a) {
        byte[] b = new byte[a.length];
        for (int i = 0; i < a.length; i++)
            b[i] = (byte)(a[i] & (byte)0x7f);
        return b;
    }
}
