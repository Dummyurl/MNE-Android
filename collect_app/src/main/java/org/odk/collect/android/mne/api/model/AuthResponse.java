package org.odk.collect.android.mne.api.model;

import com.google.gson.annotations.SerializedName;

public class AuthResponse {
    @SerializedName("token")
    private String token;

    @SerializedName("data")
    String message;


    public String getToken() {
        return token;
    }

    public String getMessage() {
        return message;
    }


}
