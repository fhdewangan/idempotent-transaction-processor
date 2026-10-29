package com.example.transactions.ingest;

import java.util.List;

import com.example.transactions.model.TransactionRequest;

public interface TransactionSource {

    String name();

    List<TransactionRequest> fetch();
}
