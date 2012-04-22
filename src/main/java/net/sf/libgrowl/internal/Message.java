/*
 * Copyright Michael Keppler
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package net.sf.libgrowl.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.sf.libgrowl.CallBackListener;
import net.sf.libgrowl.CallBackResponse;
import net.sf.libgrowl.NotificationResponse;
import net.sf.libgrowl.SubscribeResponse;

public abstract class Message implements IProtocol {
	  
  private static ExecutorService _executorService = Executors.newCachedThreadPool();

  private StringBuilder mBuffer;
  private IResponse response;
  private boolean callBack;
  private CallBackListener listener;
  
  /**
   * container for all resources which need to be sent to Growl
   */
  private HashMap<String, byte[]> mResources = new HashMap<String, byte[]>();

  /**
   * name of the sending machine
   */
  protected static String MACHINE_NAME;

  static {
    try {
      MACHINE_NAME = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  /**
   * platform version of the sending machine
   */
  private static String PLATFORM_VERSION = System.getProperty("os.version");

  /**
   * platform of the sending machine
   */
  private static String PLATFORM_NAME = System.getProperty("os.name");

  protected Message(final String messageType, String password) {
    mBuffer = new StringBuilder();
    
    if (password.equals("")) {
        mBuffer.append(IProtocol.GNTP_VERSION).append(' ').append(messageType)
        .append(' ').append(IProtocol.ENCRYPTION_NONE).append(IProtocol.LINE_BREAK);
    } else {
        mBuffer.append(IProtocol.GNTP_VERSION).append(' ').append(messageType)
        .append(' ').append(IProtocol.ENCRYPTION_NONE).append(' ')
        .append(IProtocol.KEY_HASH_MD5).append(':').append(Encryption.generateKeyHash(password))
        .append(IProtocol.LINE_BREAK);
    }

    header(HEADER_ORIGIN_MACHINE_NAME, MACHINE_NAME);
    header(HEADER_ORIGIN_SOFTWARE_NAME, "libgrowl");
    header(HEADER_ORIGIN_SOFTWARE_VERSION, "0.1");
    header(HEADER_ORIGIN_PLATFORM_NAME, PLATFORM_NAME);
    header(HEADER_ORIGIN_PLATFORM_VERSION, PLATFORM_VERSION);
  }

  protected void header(final String headerName,
      final boolean value) {
    header(headerName, value ? "True" : "False");
  }

  protected void header(final String headerName,
      final String value) {
    // filter out any \r\n in the header values
    mBuffer.append(headerName).append(": ").append(
        value.replaceAll(IProtocol.LINE_BREAK, "\n")).append(
        IProtocol.LINE_BREAK);
  }

  protected void header(final String headerName,
      final int value) {
    header(headerName, String.valueOf(value));
  }

  protected void lineBreak() {
    mBuffer.append(IProtocol.LINE_BREAK);
  }

  public int send(final String host, int port) {
    try {
      final Socket socket = new Socket(host, port);
      socket.setSoTimeout(15000);
      final BufferedReader in = new BufferedReader(new InputStreamReader(socket
          .getInputStream()));
      final OutputStreamWriter out = new OutputStreamWriter(socket
          .getOutputStream(), "UTF-8");
      final PrintWriter writer = new PrintWriter(out);

      String messageText = mBuffer.toString();

      writer.write(messageText);
      writer.flush();

      writeResources(socket);

      writer.write(IProtocol.LINE_BREAK + IProtocol.LINE_BREAK);
      writer.flush();
      //System.out.println("------------------------");
      //System.out.println(messageText);

      StringBuilder buffer = new StringBuilder();
      String line = in.readLine();
      while (line != null && !line.isEmpty()) {
        buffer.append(line).append(IProtocol.LINE_BREAK);
        line = in.readLine();
      }
      
      String responseString = buffer.toString();      
      response = GetResponseObject(responseString);
      //System.out.println("------------------------");
      //System.out.println(response);
      
      if (isCallBack()) {
        Runnable runnable = new Runnable() {
          public void run() {
            try {
              socket.setSoTimeout(0);

              StringBuilder buffer = new StringBuilder();
              boolean callBackReceived = false;
              String line = in.readLine();

              while (line != null && !(callBackReceived && line.isEmpty())) {
                buffer.append(line).append(IProtocol.LINE_BREAK);
                  
                if (line.contains("CALLBACK")) {
                  	callBackReceived = true;	
                }
 
                line = in.readLine();
              }
          	    
          	  String responseString = buffer.toString();      
              response = GetResponseObject(responseString);
              
              handleCallback(response);
              
              writer.close();
              out.close();
        	  in.close();
        	  socket.close();
			}
            catch (IOException e) {
            	// log error
            }
          }
        };
    	
        _executorService.submit(runnable);
      }
      else {
    	writer.close();
    	out.close();
    	in.close();
    	socket.close();
      }

    } catch (UnknownHostException e) {
      return IResponse.ERROR;
    } catch (IOException e) {
      return IResponse.ERROR;
    }
    return response.getStatus();
  }

  private void handleCallback(IResponse response) throws IOException {
      if (response instanceof CallBackResponse) {
    	  CallBackResponse callBackResponse = (CallBackResponse)response;
    	  
    	  String callBackResult = callBackResponse.getNotificationCallbackResult();
    	  
    	  if (callBackResult.contains("CLICK")) {
    		  listener.onClick(callBackResponse);    		  
    	  }
    	  else if (callBackResult.contains("CLOSE")) {
    		  listener.onClose(callBackResponse);    		  
    	  }
    	  else if (callBackResult.contains("TIMEOUT")) {
    		  listener.onTimeout(callBackResponse);    		  
    	  }
      }
  }
  
  private IResponse GetResponseObject(String responseString) {
	if (responseString.contains(IProtocol.MESSAGETYPE_CALLBACK)) {
		return new CallBackResponse(responseString);
	} else if (responseString.contains(IProtocol.HEADER_RESPONSE_ACTION + ": " + IProtocol.MESSAGETYPE_REGISTER)) {
		return new GenericResponse(responseString);
	} else if (responseString.contains(IProtocol.HEADER_RESPONSE_ACTION + ": " + IProtocol.MESSAGETYPE_SUBSCRIBE)) {
		return new SubscribeResponse(responseString);
	} else if (responseString.contains(IProtocol.HEADER_RESPONSE_ACTION + ": " + IProtocol.MESSAGETYPE_NOTIFY)) {
		return new NotificationResponse(responseString);
	}
	
	return new GenericResponse(responseString);
	  
  }
  
  public IResponse getResponse() {
	return response;
  }

/**
   * write the collected resources to the output stream
 * @throws IOException 
   */
  private void writeResources(Socket socket) throws IOException {
    OutputStream output = socket.getOutputStream();
    OutputStreamWriter out = new OutputStreamWriter(socket
        .getOutputStream(), "UTF-8");
    PrintWriter writer = new PrintWriter(out);

    for (Map.Entry<String, byte[]> entry : mResources.entrySet()) {
      mBuffer = new StringBuilder();
      lineBreak();
      final String id = entry.getKey();
      byte[] data = entry.getValue();
      if (data == null) {
        data = new byte[0];
      }
      header(IProtocol.HEADER_IDENTIFIER, id);
      header(IProtocol.HEADER_LENGTH, data.length);
      lineBreak();

      writer.write(mBuffer.toString());
      writer.flush();
      output.write(data);
    }
	
	lineBreak();
  }

  /**
   * add a resource to the internally remembered resources, which are
   * automatically added to the end of the message
   *
   * @param resourceId
   * @param data
   */
  protected void addResourceInternal(final String resourceId, final byte[] data) {
    mResources.put(resourceId, data);
  }
  
  protected void setCallBack(boolean callBack) {
	this.callBack = callBack;
  }

  public boolean isCallBack() {
	return callBack;
  }

  protected void setCallBackListener(CallBackListener listener) {
	this.listener = listener;
  }
  
}
