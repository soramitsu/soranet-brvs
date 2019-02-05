package iroha.validation.config;

import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.transactions.storage.TransactionProvider;
import iroha.validation.validators.Validator;
import java.util.Collection;
import java.util.Objects;

public class ValidationServiceContext {

  private final Collection<Validator> validators;
  private final TransactionProvider transactionProvider;
  private final TransactionSigner transactionSigner;

  public ValidationServiceContext(
      Collection<Validator> validators,
      TransactionProvider transactionProvider,
      TransactionSigner transactionSigner) {
    Objects.requireNonNull(validators, "Validators collection must not be null");
    // TODO rework isEmpty check during springify task
    if(validators.isEmpty()) throw new IllegalArgumentException("Validators collection must not be empty");
    Objects.requireNonNull(transactionProvider, "Transaction provider must not be null");
    Objects.requireNonNull(transactionSigner, "Transaction signer must not be null");

    this.validators = validators;
    this.transactionProvider = transactionProvider;
    this.transactionSigner = transactionSigner;
  }

  public Collection<Validator> getValidators() {
    return validators;
  }

  public TransactionProvider getTransactionProvider() {
    return transactionProvider;
  }

  public TransactionSigner getTransactionSigner() {
    return transactionSigner;
  }
}
