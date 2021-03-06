/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl;

import static iroha.validation.exception.BrvsErrorCode.REGISTRATION_FAILED;
import static jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter;

import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.protocol.TransactionOuterClass.Transaction.Payload;
import iroha.protocol.TransactionOuterClass.Transaction.Payload.ReducedPayload;
import iroha.validation.exception.BrvsException;
import iroha.validation.transactions.core.provider.RegistrationProvider;
import iroha.validation.transactions.plugin.PluggableLogic;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Newly created accounts registrations processor
 */
public class RegistrationReactionPluggableLogic extends PluggableLogic<List<String>> {

  private final RegistrationProvider registrationProvider;

  public RegistrationReactionPluggableLogic(
      RegistrationProvider registrationProvider) {
    Objects.requireNonNull(
        registrationProvider,
        "RegistrationProvider must not be null"
    );
    this.registrationProvider = registrationProvider;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<String> filterAndTransform(Block block) {
    final List<Transaction> transactions = block.getBlockV1().getPayload().getTransactionsList();
    if (transactions == null || transactions.isEmpty()) {
      return Collections.emptyList();
    }

    final Set<String> userDomains = registrationProvider.getUserDomains();
    return transactions.stream()
        .map(Transaction::getPayload)
        .map(Payload::getReducedPayload)
        .map(ReducedPayload::getCommandsList)
        .flatMap(Collection::stream)
        .filter(Command::hasCreateAccount)
        .map(Command::getCreateAccount)
        .filter(command -> userDomains.contains(command.getDomainId()))
        .map(command -> command.getAccountName().concat(accountIdDelimiter)
            .concat(command.getDomainId()))
        .filter(registrationProvider::isUserAccount)
        .collect(Collectors.toList());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void applyInternal(List<String> processableObject) {
    if (processableObject.isEmpty()) {
      return;
    }
    try {
      registrationProvider.register(processableObject);
    } catch (InterruptedException e) {
      throw new BrvsException("Couldn't register accounts", e, REGISTRATION_FAILED);
    }
  }
}
