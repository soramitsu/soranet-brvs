/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.signatory.impl;

import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import iroha.protocol.Commands.Command;
import iroha.protocol.Endpoint.ToriiResponse;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.java.detail.BuildableAndSignable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

public class TransactionSignerImpl implements TransactionSigner {

  private static final Logger logger = LoggerFactory.getLogger(TransactionSignerImpl.class);
  private static final KeyPair fakeKeyPair = Utils.parseHexKeypair(
      "0000000000000000000000000000000000000000000000000000000000000000",
      "0000000000000000000000000000000000000000000000000000000000000000"
  );

  private final IrohaAPI irohaAPI;
  private final String brvsAccountId;
  private final KeyPair brvsAccountKeyPair;
  private final List<KeyPair> keyPairs;
  private final TransactionVerdictStorage transactionVerdictStorage;
  private final Scheduler scheduler = Schedulers.from(Executors.newCachedThreadPool());

  public TransactionSignerImpl(IrohaAPI irohaAPI,
      List<KeyPair> keyPairs,
      String brvsAccountId,
      KeyPair brvsAccountKeyPair,
      TransactionVerdictStorage transactionVerdictStorage) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (CollectionUtils.isEmpty(keyPairs)) {
      throw new IllegalArgumentException("Keypairs must not be neither null nor empty");
    }
    if (StringUtils.isEmpty(brvsAccountId)) {
      throw new IllegalArgumentException("Brvs account id must not be neither null nor empty");
    }
    Objects.requireNonNull(brvsAccountKeyPair, "Brvs key pair must not be null");
    Objects.requireNonNull(keyPairs, "TransactionVerdictStorage must not be null");

    this.irohaAPI = irohaAPI;
    this.brvsAccountId = brvsAccountId;
    this.brvsAccountKeyPair = brvsAccountKeyPair;
    this.keyPairs = keyPairs;
    this.transactionVerdictStorage = transactionVerdictStorage;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void signAndSend(TransactionBatch transactionBatch) {
    for (Transaction transaction : transactionBatch) {
      transactionVerdictStorage.markTransactionValidated(ValidationUtils.hexHash(transaction));
    }
    logger.info("Transactions has been successfully validated and signed");
    if (isCreatedByBrvs(transactionBatch)) {
      sendBrvsTransactionBatch(transactionBatch, brvsAccountKeyPair);
    } else {
      sendUserTransactionBatch(transactionBatch);
    }
  }

  private boolean isCreatedByBrvs(TransactionBatch transactionBatch) {
    return StreamSupport.stream(transactionBatch.spliterator(), false)
        .anyMatch(transaction -> brvsAccountId
            .equals(transaction.getPayload().getReducedPayload().getCreatorAccountId()));
  }

  private void sendUserTransactionBatch(TransactionBatch transactionBatch) {
    final List<Transaction> transactions = new ArrayList<>(
        transactionBatch.getTransactionList().size()
    );
    for (Transaction transaction : transactionBatch) {
      final jp.co.soramitsu.iroha.java.Transaction parsedTransaction = jp.co.soramitsu.iroha.java.Transaction
          .parseFrom(transaction);
      final int signaturesCount = transaction.getSignaturesCount();
      if (signaturesCount > keyPairs.size()) {
        throw new IllegalStateException(
            "Too many user signatures in the transaction: " + signaturesCount +
                ". Key list size is " + keyPairs.size());
      }
      // Since we assume brvs signatures must be as many as users
      for (int i = 0; i < signaturesCount; i++) {
        parsedTransaction.sign(keyPairs.get(i));
      }
      transactions.add(parsedTransaction.build());
    }
    sendTransactions(transactions, true);
  }

  private void sendTransactions(List<Transaction> transactions, boolean check) {
    if (transactions.size() > 1) {
      irohaAPI.transactionListSync(transactions);
      for (Transaction transaction : transactions) {
        if (check) {
          checkIrohaStatus(transaction);
        }
      }
    } else {
      final Transaction transaction = transactions.get(0);
      irohaAPI.transactionSync(transaction);
      if (check) {
        checkIrohaStatus(transaction);
      }
    }
  }

  private void checkIrohaStatus(Transaction transaction) {
    final ToriiResponse statusResponse = ValidationUtils.subscriptionStrategy
        .subscribe(irohaAPI, Utils.hash(transaction))
        .subscribeOn(scheduler).blockingLast();
    if (!statusResponse.getTxStatus().equals(TxStatus.COMMITTED)) {
      logger.warn("Transaction " + ValidationUtils.hexHash(transaction) + " failed in Iroha: "
          + statusResponse.getTxStatus());
      transactionVerdictStorage.markTransactionFailed(
          ValidationUtils.hexHash(transaction),
          statusResponse.getTxStatus() + " : " + statusResponse.getErrOrCmdName()
      );
    }
  }

  private void sendRejectedUserTransaction(TransactionBatch transactionBatch) {
    final List<Transaction> transactions = new ArrayList<>(
        transactionBatch.getTransactionList().size()
    );
    for (Transaction transaction : transactionBatch) {
      final jp.co.soramitsu.iroha.java.Transaction parsedTransaction = jp.co.soramitsu.iroha.java.Transaction
          .parseFrom(transaction);
      final int signaturesCount = transaction.getSignaturesCount();

      // Since we assume brvs signatures must be as many as users
      for (int i = 0; i < signaturesCount; i++) {
        parsedTransaction.sign(fakeKeyPair);
      }
      transactions.add(parsedTransaction.build());
    }
    sendTransactions(transactions, false);
  }

  private void sendBrvsTransactionBatch(TransactionBatch transactionBatch, KeyPair keyPair) {
    for (Transaction transaction : transactionBatch) {
      for (Command command : transaction.getPayload().getReducedPayload().getCommandsList()) {
        // Do not sign set acc quorum about user account if its time is synchronized
        // There will be a multisig transaction sync on time
        // Instead of many fully signed same transactions
        if (transaction.getPayload().getReducedPayload().getCreatedTime() % 1000000 == 0 &&
            (
                (command.hasSetAccountQuorum() && !brvsAccountId
                    .equals(command.getSetAccountQuorum().getAccountId()))
                    ||
                    // Do not sign setAccDetails (user quorum) for same reason
                    (command.hasSetAccountDetail())
            )
        ) {
          return;
        }
      }
    }

    final List<Transaction> transactions = transactionBatch.getTransactionList()
        .stream()
        .map(jp.co.soramitsu.iroha.java.Transaction::parseFrom)
        .map(transaction -> transaction.sign(keyPair))
        .map(BuildableAndSignable::build)
        .collect(Collectors.toList());

    sendTransactions(transactions, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rejectAndSend(TransactionBatch transactionBatch, String reason) {
    for (Transaction transaction : transactionBatch) {
      transactionVerdictStorage.markTransactionRejected(
          ValidationUtils.hexHash(transaction),
          reason
      );
    }
    logger.info("Transactions has been rejected by the service. Reason: " + reason);
    if (isCreatedByBrvs(transactionBatch)) {
      sendBrvsTransactionBatch(transactionBatch, fakeKeyPair);
    } else {
      sendRejectedUserTransaction(transactionBatch);
    }
  }
}
