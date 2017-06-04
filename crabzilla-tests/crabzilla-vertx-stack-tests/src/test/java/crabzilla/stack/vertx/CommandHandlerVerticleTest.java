package crabzilla.stack.vertx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import crabzilla.example1.aggregates.customer.Customer;
import crabzilla.example1.aggregates.customer.CustomerId;
import crabzilla.example1.aggregates.customer.CustomerSupplierFn;
import crabzilla.example1.aggregates.customer.commands.CreateCustomerCmd;
import crabzilla.example1.aggregates.customer.events.CustomerCreated;
import crabzilla.model.*;
import crabzilla.model.util.Eithers;
import crabzilla.stack.EventRepository;
import crabzilla.stack.SnapshotMessage;
import crabzilla.stack.SnapshotReaderFn;
import crabzilla.stack.vertx.codecs.fst.JacksonGenericCodec;
import crabzilla.stack.vertx.verticles.CommandHandlerVerticle;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.Json;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import lombok.Value;
import lombok.val;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.nustaq.serialization.FSTConfiguration;

import java.util.Optional;
import java.util.UUID;

import static crabzilla.stack.util.StringHelper.commandHandlerId;
import static crabzilla.stack.vertx.CommandExecution.RESULT;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@RunWith(VertxUnitRunner.class)
public class CommandHandlerVerticleTest {

  static final FSTConfiguration fst = FSTConfiguration.createDefaultConfiguration();

  Vertx vertx;
  Cache<String, Snapshot<Customer>> cache;
  CircuitBreaker circuitBreaker;

  @Mock
  CommandValidatorFn validatorFn;
  @Mock
  SnapshotReaderFn<Customer> snapshotReaderFn;
  @Mock
  CommandHandlerFn<Customer> cmdHandlerFn;
  @Mock
  EventRepository eventRepository;

  Vertx vertx() {

    val vertx = Vertx.vertx();

    val mapper = Json.mapper;
    mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
    mapper.findAndRegisterModules();

    vertx.eventBus().registerDefaultCodec(CommandExecution.class,
            new JacksonGenericCodec<>(mapper, CommandExecution.class));

    vertx.eventBus().registerDefaultCodec(AggregateRootId.class,
            new JacksonGenericCodec<>(mapper, AggregateRootId.class));

    vertx.eventBus().registerDefaultCodec(Command.class,
            new JacksonGenericCodec<>(mapper, Command.class));

    vertx.eventBus().registerDefaultCodec(Event.class,
            new JacksonGenericCodec<>(mapper, Event.class));

    vertx.eventBus().registerDefaultCodec(UnitOfWork.class,
            new JacksonGenericCodec<>(mapper, UnitOfWork.class));

    return vertx;
  }

  @Before
  public void setUp(TestContext context) {

    MockitoAnnotations.initMocks(this);

    vertx = vertx();
    cache = Caffeine.newBuilder().build();
    circuitBreaker = CircuitBreaker.create("cmd-handler-circuit-breaker", vertx,
            new CircuitBreakerOptions()
                    .setMaxFailures(5) // number SUCCESS failure before opening the circuit
                    .setTimeout(2000) // consider a failure if the operation does not succeed in time
                    .setFallbackOnFailure(true) // do we call the fallback on failure
                    .setResetTimeout(10000) // time spent in open state before attempting to re-try
    );

    val verticle = new CommandHandlerVerticle<Customer>(Customer.class, snapshotReaderFn, cmdHandlerFn,
                              validatorFn, eventRepository, cache, vertx, circuitBreaker);

    vertx.deployVerticle(verticle, context.asyncAssertSuccess());

  }

  @After
  public void tearDown(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
  }

  @Test
  public void valid_command_get_SUCCESS(TestContext tc) {

    Async async = tc.async();

    val customerId = new CustomerId("customer#1");
    val createCustomerCmd = new CreateCustomerCmd(UUID.randomUUID(), customerId, "customer");
    val expectedSnapshot = new Snapshot<Customer>(new CustomerSupplierFn().get(), new Version(0));
    val expectedMessage = new SnapshotMessage<Customer>(expectedSnapshot, SnapshotMessage.LoadedFromEnum.FROM_DB);
    val expectedEvent = new CustomerCreated(createCustomerCmd.getTargetId(), "customer");
    val expectedUow = UnitOfWork.of(createCustomerCmd, new Version(1), singletonList(expectedEvent));

    when(validatorFn.constraintViolations(eq(createCustomerCmd))).thenReturn(emptyList());
    when(snapshotReaderFn.getSnapshotMessage(eq(customerId.getStringValue()))).thenReturn(expectedMessage);
    when(cmdHandlerFn.handle(eq(createCustomerCmd), eq(expectedSnapshot)))
            .thenReturn(Eithers.right(Optional.of(expectedUow)));
    when(eventRepository.append(any(UnitOfWork.class))).thenReturn(1L);

    val options = new DeliveryOptions().setCodecName("Command");

    vertx.eventBus().send(commandHandlerId(Customer.class), createCustomerCmd, options, asyncResult -> {

      verify(validatorFn).constraintViolations(eq(createCustomerCmd));
      verify(snapshotReaderFn).getSnapshotMessage(eq(createCustomerCmd.getTargetId().getStringValue()));
      verify(cmdHandlerFn).handle(createCustomerCmd, expectedSnapshot);

      ArgumentCaptor<UnitOfWork> argument = ArgumentCaptor.forClass(UnitOfWork.class);
      verify(eventRepository).append(argument.capture());

      verifyNoMoreInteractions(validatorFn, snapshotReaderFn, cmdHandlerFn, eventRepository);

      tc.assertTrue(asyncResult.succeeded());

      val response = (CommandExecution) asyncResult.result().body();

      tc.assertEquals(RESULT.SUCCESS, response.getResult());
      tc.assertEquals(1L, response.getUowSequence().get());

      val resultUnitOfWork = response.getUnitOfWork().get();

      tc.assertEquals(expectedUow.getCommand(), resultUnitOfWork.getCommand());
      tc.assertEquals(expectedUow.getEvents(), resultUnitOfWork.getEvents());
      tc.assertEquals(expectedUow.getVersion(), resultUnitOfWork.getVersion());

      async.complete();

    });

  }

  @Test
  public void an_invalid_command_get_VALIDATION_ERROR(TestContext tc) {

    Async async = tc.async();

    val customerId = new CustomerId("customer#1");

    val createCustomerCmd = new CreateCustomerCmd(UUID.randomUUID(), customerId, "customer1");
    val expectedSnapshot = new Snapshot<Customer>(new CustomerSupplierFn().get(), new Version(0));
    val expectedMessage = new SnapshotMessage<Customer>(expectedSnapshot, SnapshotMessage.LoadedFromEnum.FROM_DB);

    when(validatorFn.constraintViolations(eq(createCustomerCmd))).thenReturn(singletonList("An error"));

    when(snapshotReaderFn.getSnapshotMessage(eq(customerId.getStringValue())))
            .thenReturn(expectedMessage);

    val options = new DeliveryOptions().setCodecName("Command");

    vertx.eventBus().send(commandHandlerId(Customer.class), createCustomerCmd, options, asyncResult -> {

      verify(validatorFn).constraintViolations(eq(createCustomerCmd));

      verifyNoMoreInteractions(validatorFn, snapshotReaderFn, cmdHandlerFn, eventRepository);

      tc.assertTrue(asyncResult.succeeded());

      val response = (CommandExecution) asyncResult.result().body();

      tc.assertEquals(RESULT.VALIDATION_ERROR, response.getResult());

      tc.assertEquals(asList("An error"), response.getConstraints().get());

      async.complete();

    });

  }

  @Value
  class UnknownCommand implements Command {
    UUID commandId;
    CustomerId targetId;
  }

  @Test
  public void an_unknown_command_get_UNKNOWN_COMMAND(TestContext tc) {

    Async async = tc.async();

    val customerId = new CustomerId("customer#1");
    val createCustomerCmd = new UnknownCommand(UUID.randomUUID(), customerId);
    val expectedSnapshot = new Snapshot<Customer>(new CustomerSupplierFn().get(), new Version(0));
    val expectedMessage = new SnapshotMessage<Customer>(expectedSnapshot, SnapshotMessage.LoadedFromEnum.FROM_DB);

    when(validatorFn.constraintViolations(eq(createCustomerCmd))).thenReturn(emptyList());
    when(snapshotReaderFn.getSnapshotMessage(eq(customerId.getStringValue())))
            .thenReturn(expectedMessage);
    when(cmdHandlerFn.handle(eq(createCustomerCmd), eq(expectedSnapshot)))
            .thenReturn(Eithers.right(Optional.empty()));

    val options = new DeliveryOptions().setCodecName("Command");

    vertx.eventBus().send(commandHandlerId(Customer.class), createCustomerCmd, options, asyncResult -> {

      verify(validatorFn).constraintViolations(eq(createCustomerCmd));
      verify(snapshotReaderFn).getSnapshotMessage(eq(createCustomerCmd.getTargetId().getStringValue()));
      verify(cmdHandlerFn).handle(createCustomerCmd, expectedSnapshot);

      verifyNoMoreInteractions(validatorFn, snapshotReaderFn, cmdHandlerFn, eventRepository);

      tc.assertTrue(asyncResult.succeeded());

      val response = (CommandExecution) asyncResult.result().body();

      tc.assertEquals(RESULT.UNKNOWN_COMMAND, response.getResult());

      async.complete();

    });

  }


}