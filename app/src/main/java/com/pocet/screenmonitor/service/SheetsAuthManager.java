package com.pocet.screenmonitor.service;

import android.content.Context;
import android.content.Intent;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.api.services.sheets.v4.Sheets;
import kotlin.coroutines.Continuation;

public interface SheetsAuthManager {

    void initialize(GoogleSignInOptions signInOptions);

    Object getSheetsService(Continuation<? super Sheets> continuation);

    Intent getSignInIntent();

    Object handleSignInResult(Intent intent, Continuation<? super GoogleSignInAccount> continuation);

    void signOut();

    boolean isSignedIn();
}