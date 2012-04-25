package net.sf.libgrowl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import net.sf.libgrowl.internal.IResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GNTPTest {
	
	/** 
     * Sets up the test fixture. 
     * (Called before every test case method.) 
     */ 
	
	private GrowlConnector growl;
	private Application downloadApp;
	private NotificationType downloadStarted;
	private NotificationType downloadFinished;
	
    @Before 
    public void setUp() { 
        // connect to Growl on the given host
        growl = new GrowlConnector("localhost");
        assertNotNull(growl);
        
//        growl.setPassword("test");
        
        // give your application a name and icon (optionally)
        downloadApp = new Application("Downloader", "http://jenkins-ci.org/images/butler.png");

        // create reusable notification types, their names are used in the Growl settings
        downloadStarted = new NotificationType("Download started");
        downloadFinished = new NotificationType("Download finished");
        NotificationType[] notificationTypes = new NotificationType[] { downloadStarted, downloadFinished };

        // now register the application in growl
        growl.register(downloadApp, notificationTypes);

    } 
    /** 
     * Tears down the test fixture. 
     * (Called after every test case method.) 
     */ 
    @After 
    public void tearDown() { 
    } 
    
    @Test
    public void testNotification() {
    	Notification ubuntuDownload = new Notification(downloadApp, downloadStarted, "Test", "Standard Notification"); 
    	
        // finally send the notification
        growl.notify(ubuntuDownload);
        
        // finally send the notification
        assertEquals(IResponse.OK, growl.notify(ubuntuDownload));
        NotificationResponse response = (NotificationResponse) growl.getLastResponse();
        
        assertEquals("NOTIFY", response.getResponseAction());
    }
    
    @Test(timeout=11000)
    public void testCallback() throws InterruptedException {

        Runnable runnable = new Runnable() {
			public void run() {
				Notification ubuntuDownload = new Notification(downloadApp, downloadStarted, "Test", "CallBack Notification");
		        
				final String context = "context";
				final String contextType = "contextType";
				
				ubuntuDownload.setCallBackSocket(context, contextType);

		        CallBackListener listener = new CallBackListener() {			
					public void onTimeout(CallBackResponse response) {
						checkCallBackResponse(response);
					}
					
					public void onClose(CallBackResponse response) {
						checkCallBackResponse(response);
					}
					
					public void onClick(CallBackResponse response) {
						checkCallBackResponse(response);
					}
					
					private void checkCallBackResponse(CallBackResponse response) {
						if (!response.getNotificationCallbackContext().equals(context) ||
				        	!response.getNotificationCallbackContextType().equals(contextType)) {
				        	
				        	synchronized(callBackTestFailed) {
				        		callBackTestFailed = true;
					        }
				        }

						synchronized(callBackTestThreadsCompleted) {
				        	callBackTestThreadsCompleted++;
				        }
					}
				};
		        
		        growl.notify(ubuntuDownload, listener);
			}
		};
        
		int notificationCount = 3;
		
		for (int i = 0; i < notificationCount; i++) {
			Thread thread = new Thread(runnable);
			
			thread.start();
		}

		// wait for all threads to finish. JUnit test will timeout after 11 seconds.
		while ((int)callBackTestThreadsCompleted < notificationCount) {
			Thread.sleep(100);
		}
		
		assertEquals(false, callBackTestFailed);
    }
    
    private static Integer callBackTestThreadsCompleted = 0;
    private static Boolean callBackTestFailed = false;

    @Test
    public void testSubscribe() {
        growl.subsribe();
        SubscribeResponse sr = (SubscribeResponse) growl.getLastResponse();
        assertEquals("300", sr.getSubscriptionTTL());

    }
         	
    
}
