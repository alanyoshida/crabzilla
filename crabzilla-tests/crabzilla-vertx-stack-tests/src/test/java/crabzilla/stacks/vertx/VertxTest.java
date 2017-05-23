package crabzilla.stacks.vertx;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Modules;
import crabzilla.UnitOfWork;
import crabzilla.Version;
import crabzilla.example1.Example1VertxModule;
import crabzilla.example1.aggregates.customer.*;
import crabzilla.example1.aggregates.customer.commands.CreateCustomerCmd;
import crabzilla.example1.aggregates.customer.events.CustomerCreated;
import crabzilla.model.CommandValidatorFn;
import crabzilla.stack.EventRepository;
import crabzilla.stack.Snapshot;
import crabzilla.stack.SnapshotMessage;
import crabzilla.stack.SnapshotReaderFn;
import crabzilla.stacks.vertx.codecs.CommandCodec;
import crabzilla.stacks.vertx.verticles.CommandHandlerVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import lombok.val;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static crabzilla.util.StringHelper.commandHandlerId;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@RunWith(VertxUnitRunner.class)
public class VertxTest {

  @Inject
  Vertx vertx;
  @Inject
  Gson gson;

  @Mock
  CommandValidatorFn validatorFn;
  @Mock
  SnapshotReaderFn<Customer> snapshotReaderFn;
  @Mock
  EventRepository eventRepository;

  Cache<String, Snapshot<Customer>> cache = Caffeine.newBuilder().build();


  @Before
  public void setUp(TestContext context) {

    MockitoAnnotations.initMocks(this);
    Guice.createInjector(Modules.override(new Example1VertxModule()).with(new AbstractModule() {
      @Override
      protected void configure() {
        bind(new TypeLiteral<SnapshotReaderFn<Customer>>() {;}).toInstance(snapshotReaderFn);
        bind(new TypeLiteral<CommandValidatorFn>() {;}).toInstance(validatorFn);
        bind(EventRepository.class).toInstance(eventRepository);
      }
    })).injectMembers(this);

    val cmdHandler = new CustomerCmdHandlerFnJavaslang(new CustomerStateTransitionFnJavaslang(), customer -> customer);

    val verticle = new CommandHandlerVerticle<Customer>(Customer.class, snapshotReaderFn, cmdHandler,
                              validatorFn, eventRepository, cache, vertx);

    vertx.deployVerticle(verticle, context.asyncAssertSuccess());

  }

  @After
  public void tearDown(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
  }

  @Test
  public void a_valid_command_must_be_handled(TestContext tc) {

    Async async = tc.async();

    val customerId = new CustomerId("customer#1");

    val createCustomerCmd = new CreateCustomerCmd(UUID.randomUUID(), customerId, "customer1");

    val expectedMessage = new SnapshotMessage<Customer>(
            new Snapshot<>(new CustomerSupplierFn().get(), new Version(0)),
            SnapshotMessage.LoadedFromEnum.FROM_DB);

    when(validatorFn.constraintViolation(eq(createCustomerCmd))).thenReturn(Optional.empty());

    when(snapshotReaderFn.getSnapshotMessage(eq(customerId.getStringValue())))
            .thenReturn(expectedMessage);

    val options = new DeliveryOptions().setCodecName(new CommandCodec(gson).name());

    vertx.eventBus().send(commandHandlerId(Customer.class), createCustomerCmd, options, asyncResult -> {

      verify(validatorFn).constraintViolation(eq(createCustomerCmd));

      verify(snapshotReaderFn).getSnapshotMessage(eq(createCustomerCmd.getTargetId().getStringValue()));

      val expectedEvent = new CustomerCreated(createCustomerCmd.getTargetId(), "customer1");
      val expectedUow = UnitOfWork.of(createCustomerCmd, new Version(1), Arrays.asList(expectedEvent));

      ArgumentCaptor<UnitOfWork> argument = ArgumentCaptor.forClass(UnitOfWork.class);

      verify(eventRepository).append(argument.capture());

      val resultingUow = argument.getValue();

      tc.assertEquals(resultingUow.getCommand(), expectedUow.getCommand());
      tc.assertEquals(resultingUow.getEvents(), expectedUow.getEvents());
      tc.assertEquals(resultingUow.getVersion(), expectedUow.getVersion());

      verifyNoMoreInteractions(validatorFn, snapshotReaderFn, eventRepository);

      async.complete();

    });

  }

  @Test
  public void an_invalid_command_must_be_handled(TestContext tc) {

    Async async = tc.async();

    val customerId = new CustomerId("customer#1");

    val createCustomerCmd = new CreateCustomerCmd(UUID.randomUUID(), customerId, "customer1");

    val expectedMessage = new SnapshotMessage<Customer>(
            new Snapshot<>(new CustomerSupplierFn().get(), new Version(0)),
            SnapshotMessage.LoadedFromEnum.FROM_DB);

    when(validatorFn.constraintViolation(eq(createCustomerCmd))).thenReturn(Optional.of("An error"));

    when(snapshotReaderFn.getSnapshotMessage(eq(customerId.getStringValue())))
            .thenReturn(expectedMessage);

    val options = new DeliveryOptions().setCodecName(new CommandCodec(gson).name());

    vertx.eventBus().send(commandHandlerId(Customer.class), createCustomerCmd, options, asyncResult -> {

      verify(validatorFn).constraintViolation(eq(createCustomerCmd));

      verifyNoMoreInteractions(validatorFn, snapshotReaderFn, eventRepository);

//      tc.assertTrue(asyncResult.failed()); // TODO why this is not failed ?

      tc.assertEquals("An error", asyncResult.result().body());

      async.complete();

    });

  }

}