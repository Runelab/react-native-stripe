
package com.guidolodetti;

import android.app.Activity;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.stripe.android.CustomerSession;
import com.stripe.android.EphemeralKeyProvider;
import com.stripe.android.EphemeralKeyUpdateListener;
import com.stripe.android.PaymentConfiguration;
import com.stripe.android.PaymentSession;
import com.stripe.android.PaymentSessionConfig;
import com.stripe.android.PaymentSessionData;
import com.stripe.android.model.Card;
import com.stripe.android.model.Customer;
import com.stripe.android.model.CustomerSource;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class RNStripeModule extends ReactContextBaseJavaModule implements PaymentSession.PaymentSessionListener {

    private EphemeralKeyUpdateListener keyUpdateListener;

    private PaymentSession mPaymentSession;

    private Promise initPaymentContextPromise;

    private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {
        @Override
        public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
            super.onActivityResult(activity, requestCode, resultCode, data);
            mPaymentSession.handlePaymentData(requestCode, resultCode, data);
        }
    };

    public RNStripeModule(ReactApplicationContext reactContext) {
        super(reactContext);

        // Add the listener for `onActivityResult`
        reactContext.addActivityEventListener(mActivityEventListener);
    }

    @Override
    public String getName() {
        return "RNStripe";
    }


    private void sendEvent(String eventName, @Nullable WritableMap params) {
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    @ReactMethod
    public void initWithOptions(ReadableMap options, Promise promise) {
        final String publishableKey = options.getString("publishableKey");
        if (publishableKey == null) {
            promise.reject("RNStripePublishableKeyRequired", "A valid Stripe PublishableKey is required");
            return;
        }

        PaymentConfiguration.init(publishableKey);

        promise.resolve(true);
    }

    @ReactMethod
    public void initPaymentContext(ReadableMap options, Promise promise) {
        if (initPaymentContextPromise != null) {
            promise.reject("RNStripeInitPaymentInProgress", "initPaymentContext already called, but initialization is not yet completed.");
            return;
        }

        initPaymentContextPromise = promise;

        // Init CustomerSession
        CustomerSession.initCustomerSession(new EphemeralKeyProvider() {
            @Override
            public void createEphemeralKey(@NonNull String apiVersion,
                                           @NonNull EphemeralKeyUpdateListener _keyUpdateListener) {
                Log.d("RNStripe", "initPaymentContext");
                keyUpdateListener = _keyUpdateListener;
                WritableMap params = Arguments.createMap();
                params.putString("apiVersion", apiVersion);

                sendEvent("RNStripeRequestedCustomerKey", params);
            }
        });

        // Init PaymentSession
        mPaymentSession = new PaymentSession(getReactApplicationContext().getCurrentActivity());
        mPaymentSession.init(this, new PaymentSessionConfig.Builder().setShippingInfoRequired(true).build());
    }

    @ReactMethod
    public void retrievedCustomerKey(ReadableMap customerKey) {
        try {
            JSONObject customerKeyJson = RNStripeUtils.convertMapToJson(customerKey);
            Log.d("RNStripe", "Stripe customer key: " + customerKeyJson.toString());
            if (keyUpdateListener != null) {
                keyUpdateListener.onKeyUpdate(customerKeyJson.toString());
            }
        } catch (JSONException e) {
            Log.e("RNStripe", "JSON Conversion error");
        }
    }

    @ReactMethod
    public void failedRetrievingCustomerKey() {
        Log.e("RNStripe", "Failed to retrieve CustomerKey");
    }

    @ReactMethod
    public void presentPaymentMethodsViewController(Promise promise) {
        mPaymentSession.presentPaymentMethodSelection();
        promise.resolve(true);
    }

    /**
     * PaymentSessionListener
     */

    @Override
    public void onCommunicatingStateChanged(boolean isCommunicating) {

    }

    @Override
    public void onError(int errorCode, @Nullable String errorMessage) {
        Toast.makeText(getReactApplicationContext(), errorMessage, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPaymentSessionDataChanged(@NonNull PaymentSessionData data) {
        final String selectedPaymentMethodId = data.getSelectedPaymentMethodId();
        CustomerSession.getInstance().retrieveCurrentCustomer(
                new CustomerSession.CustomerRetrievalListener() {
                    @Override
                    public void onCustomerRetrieved(@NonNull Customer customer) {
                        final CustomerSource displaySource = customer.getSourceById(selectedPaymentMethodId);
                        if (displaySource == null) {
                            if (initPaymentContextPromise != null) {
                                initPaymentContextPromise.resolve(null);
                                initPaymentContextPromise = null;
                            }
                            return;
                        }

                        final Card customerCard = displaySource.asCard();
                        if (customerCard == null) {
                            if (initPaymentContextPromise != null) {
                                initPaymentContextPromise.resolve(null);
                                initPaymentContextPromise = null;
                            }
                            return;
                        }

                        final Map<String, Object> selectedCardDetails = new HashMap<>();
                        selectedCardDetails.put("brand", customerCard.getBrand());
                        selectedCardDetails.put("last4", customerCard.getLast4());
                        Log.e("RNStripe", selectedCardDetails.toString());
                        // Send the card information to JS
                        RNStripeModule.this.sendEvent("RNStripeSelectedPaymentMethodDidChange", Arguments.makeNativeMap(selectedCardDetails));

                        if (initPaymentContextPromise != null) {
                            initPaymentContextPromise.resolve(Arguments.makeNativeMap(selectedCardDetails));
                            initPaymentContextPromise = null;
                        }
                    }

                    @Override
                    public void onError(int errorCode, @Nullable String errorMessage) {
                        Toast.makeText(getReactApplicationContext(), errorMessage, Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }
}
