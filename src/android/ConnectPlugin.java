package org.apache.cordova.facebook;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Iterator;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import com.facebook.android.DialogError;
import com.facebook.android.Facebook;
import com.facebook.android.Facebook.DialogListener;
import com.facebook.android.FacebookError;

public class ConnectPlugin extends CordovaPlugin {

    public static final String SINGLE_SIGN_ON_DISABLED = "service_disabled";
    private final String TAG = "ConnectPlugin";

    private Facebook facebook;
    private String userId;
    //used for dialog auth
    private String[] permissions = new String[] {};
    private CallbackContext _callbackContext;
    private Bundle paramBundle;
    private String method;

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) {
        _callbackContext = callbackContext;

        if (action.equals("init")) {
            try {
                String appId = args.getString(0);

                facebook = new Facebook(appId);

                Log.d(TAG, "init: Initializing plugin.");

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(cordova.getActivity());
                String access_token = prefs.getString("access_token", null);
                Long expires = prefs.getLong("access_expires", -1);

                if (access_token != null && expires != -1) {
                    this.facebook.setAccessToken(access_token);
                    this.facebook.setAccessExpires(expires);
                    try {
                        JSONObject o = new JSONObject(this.facebook.request("/me"));
                        this.userId = o.getString("id");
                    } catch (MalformedURLException e) {

                        e.printStackTrace();
                    } catch (IOException e) {

                        e.printStackTrace();
                    } catch (JSONException e) {

                        e.printStackTrace();
                    }
                }

                if(facebook.isSessionValid() && this.userId != null) {
                    _callbackContext.success(this.getResponse());
                    return true;
                }
                else {
                    _callbackContext.success();
                    return true;
                }
            } catch (JSONException e) {

                e.printStackTrace();
                _callbackContext.error("Invalid JSON args used. expected a string as the first arg.");
                return false;
            }
        }

        else if (action.equals("login")) {
            if (facebook != null) {
            	final ConnectPlugin me = this;
                String[] permissions = new String[args.length()];
                try {
                    for (int i=0; i<args.length(); i++) {
                        permissions[i] = args.getString(i);
                    }
                } catch (JSONException e1) {

                    e1.printStackTrace();
                    _callbackContext.error("Invalid JSON args used. Expected a string array of permissions.");
                    return false;
                }
                cordova.setActivityResultCallback(this);
                this.permissions = permissions;
                Runnable runnable = new Runnable() {
                    public void run() {
                        me.facebook.authorize(cordova.getActivity(), me.permissions, new AuthorizeListener(me));
                    };
                };
                cordova.getActivity().runOnUiThread(runnable);
                return true;
            } else {
                _callbackContext.error("Must call init before login.");
                return false;
            }
        }

        else if (action.equals("logout")) {
            if (facebook != null) {
                try {
                    facebook.logout(cordova.getActivity());

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.cordova.getActivity());
                    prefs.edit().putLong("access_expires", -1).commit();
                    prefs.edit().putString("access_token", null).commit();
                } catch (MalformedURLException e) {

                    e.printStackTrace();
                    _callbackContext.error("Error logging out.");
                    return false;
                } catch (IOException e) {

                    e.printStackTrace();
                    _callbackContext.error("Error logging out.");
                    return false;
                }
                _callbackContext.success(getResponse());
                return true;
            } else {
                _callbackContext.error("Must call init before logout.");
                return false;
            }
        }

        else if (action.equals("getLoginStatus")) {
            if (facebook != null) {
                _callbackContext.success(getResponse());
                return true;
            } else {
                _callbackContext.error("Must call init before getLoginStatus.");
                return false;
            }
        }

        else if (action.equals("showDialog")) {
        	if (facebook != null) {
        		Bundle collect = new Bundle();
        		JSONObject params = null;
        		try {
        			params = args.getJSONObject(0);
        		} catch (JSONException e) {
        			params = new JSONObject();
        		}

        		final ConnectPlugin me = this;
        		Iterator<?> iter = params.keys();
        		while (iter.hasNext()) {
        			String key = (String) iter.next();
        			if (key.equals("method")) {
        				try {
        					this.method = params.getString(key);
        				} catch (JSONException e) {
        					Log.w(TAG, "Nonstring method parameter provided to dialog");
        				}
        			} else {
        				try {
        					collect.putString(key, params.getString(key));
        				} catch (JSONException e) {
        					// Need to handle JSON parameters
        					Log.w(TAG, "Nonstring parameter provided to dialog discarded");
        				}
        			}
        		}
        		this.paramBundle =  new Bundle(collect);
        		Runnable runnable = new Runnable() {
        			public void run() {
        				me.facebook.dialog (me.cordova.getActivity(), me.method , me.paramBundle , new UIDialogListener(me));
        			};
        		};
        		cordova.getActivity().runOnUiThread(runnable);
        		return true;
        	} else {
        		_callbackContext.error("Must call init before showDialog.");
        		return false;
        	}

        }

        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        facebook.authorizeCallback(requestCode, resultCode, data);
    }

    public JSONObject getResponse() {
    	String response;
    	if (facebook.isSessionValid()) {
    		long expiresTimeInterval = facebook.getAccessExpires() - System.currentTimeMillis();
    		long expiresIn = (expiresTimeInterval > 0) ? expiresTimeInterval : 0;
    		response = "{"+
            "\"status\": \"connected\","+
            "\"authResponse\": {"+
              "\"accessToken\": \""+facebook.getAccessToken()+"\","+
              "\"expiresIn\": \""+expiresIn+"\","+
              "\"session_key\": true,"+
              "\"sig\": \"...\","+
              "\"userID\": \""+this.userId+"\""+
            "}"+
          "}";
    	} else {
    		response = "{"+
            "\"status\": \"unknown\""+
          "}";
    	}

        try {
            return new JSONObject(response);
        } catch (JSONException e) {

            e.printStackTrace();
        }
        return new JSONObject();
    }

    class UIDialogListener implements DialogListener {
   	 final ConnectPlugin fba;

		public UIDialogListener(ConnectPlugin fba){
			super();
			this.fba = fba;
		}

		public void onComplete(Bundle values) {
			Log.d(TAG,values.toString());
			this.fba._callbackContext.success();
		}

		public void onFacebookError(FacebookError e) {
           Log.d(TAG, "facebook error");
           this.fba._callbackContext.error("Facebook error: " + e.getMessage());
       }

       public void onError(DialogError e) {
           Log.d(TAG, "other error");
           this.fba._callbackContext.error("Dialog error: " + e.getMessage());
       }

       public void onCancel() {
           Log.d(TAG, "cancel");
           this.fba._callbackContext.error("Cancelled");
       }
	}

    class AuthorizeListener implements DialogListener {
        final ConnectPlugin fba;

        public AuthorizeListener(ConnectPlugin fba){
            super();
            this.fba = fba;
        }

        public void onComplete(Bundle values) {
            //  Handle a successful login

            String token = this.fba.facebook.getAccessToken();
            long token_expires = this.fba.facebook.getAccessExpires();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.fba.cordova.getActivity());
            prefs.edit().putLong("access_expires", token_expires).commit();
            prefs.edit().putString("access_token", token).commit();

            Log.d(TAG, "authorized");

            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        JSONObject o = new JSONObject(fba.facebook.request("/me"));
                        fba.userId = o.getString("id");
                        final JSONObject response = getResponse();
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                _callbackContext.success(response);
                            }
                        });
                    } catch (MalformedURLException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    } catch (JSONException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            });
            t.start();
        }

        public void onFacebookError(FacebookError e) {
            Log.d(TAG, "facebook error");
            this.fba._callbackContext.error("Facebook error: " + e.getMessage());
        }

        public void onError(DialogError e) {
            Log.d(TAG, "other error");
            this.fba._callbackContext.error("Dialog error: " + e.getMessage());
        }

        public void onCancel() {
            Log.d(TAG, "cancel");
            this.fba._callbackContext.error("Cancelled");
        }
    }
}
