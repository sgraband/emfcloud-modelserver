/********************************************************************************
 * Copyright (c) 2019 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0, or the MIT License which is
 * available at https://opensource.org/licenses/MIT.
 *
 * SPDX-License-Identifier: EPL-2.0 OR MIT
 ********************************************************************************/
package org.eclipse.emfcloud.modelserver.emf.common;

import static java.util.stream.Collectors.toSet;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.BasicDiagnostic;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emfcloud.modelserver.command.CCommand;
import org.eclipse.emfcloud.modelserver.common.codecs.EncodingException;
import org.eclipse.emfcloud.modelserver.emf.common.codecs.Codecs;
import org.emfjson.jackson.module.EMFModule;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsHandler;

public class SessionController extends WsHandler {

   private static Logger LOG = Logger.getLogger(SessionController.class.getSimpleName());

   private final Map<String, Set<WsContext>> modelUrisToClients = Maps.newConcurrentMap();

   private final Map<String, Set<WsContext>> modelUrisToClientsValidation = Maps.newConcurrentMap();

   private final Map<String, BasicDiagnostic> modelUriToLastSendDiagnostic = Maps.newConcurrentMap();

   @Inject
   private ModelRepository modelRepository;

   private final Codecs encoder;

   // Primarily for testability because the final session field cannot be mocked
   private Predicate<? super WsContext> isOpenPredicate = ctx -> ctx.session.isOpen();

   public SessionController() {
      this.encoder = new Codecs();
   }

   public boolean subscribe(final WsContext ctx, final String modeluri) {
      if (this.modelRepository.hasModel(modeluri)) {
         modelUrisToClients.computeIfAbsent(modeluri, clients -> ConcurrentHashMap.newKeySet()).add(ctx);
         ctx.send(JsonResponse.success(ctx.getSessionId() + "/Changes"));
         return true;
      }
      return false;
   }

   public boolean subscribeToValidation(final WsContext ctx, final String modeluri) {
      if (this.modelRepository.hasModel(modeluri)) {
         modelUrisToClientsValidation.computeIfAbsent(modeluri, clients -> ConcurrentHashMap.newKeySet()).add(ctx);
         ctx.send(JsonResponse.success(ctx.getSessionId() + "/Validation"));
         return true;
      }
      return false;
   }

   public boolean unsubscribe(final WsContext ctx) {
      if (!this.isClientSubscribed(ctx)) {
         return false;
      }

      Iterator<Map.Entry<String, Set<WsContext>>> it = modelUrisToClients.entrySet().iterator();

      while (it.hasNext()) {
         Map.Entry<String, Set<WsContext>> entry = it.next();
         Set<WsContext> clients = entry.getValue();
         clients.remove(ctx);
         if (clients.isEmpty()) {
            it.remove();
         }
      }

      return true;
   }

   public boolean unsubscribeToValidation(final WsContext ctx) {
      if (!this.isClientSubscribed(ctx)) {
         return false;
      }

      Iterator<Map.Entry<String, Set<WsContext>>> it = modelUrisToClientsValidation.entrySet().iterator();

      while (it.hasNext()) {
         Map.Entry<String, Set<WsContext>> entry = it.next();
         Set<WsContext> clients = entry.getValue();
         clients.remove(ctx);
         if (clients.isEmpty()) {
            it.remove();
         }
      }

      return true;
   }

   public void modelChanged(final String modeluri) {
      modelRepository.getModel(modeluri).ifPresentOrElse(
         eObject -> {
            broadcastFullUpdate(modeluri, eObject);
            broadcastDirtyState(modeluri, true);
            broadcastValidationResult(modeluri);
         },
         () -> broadcastError(modeluri, "Could not load changed object"));
   }

   public void modelChanged(final String modeluri, final CCommand command) {
      modelRepository.getModel(modeluri).ifPresentOrElse(
         eObject -> {
            broadcastIncrementalUpdate(modeluri, command);
            broadcastDirtyState(modeluri, true);
            broadcastValidationResult(modeluri);
         },
         () -> broadcastError(modeluri, "Could not load changed object"));
   }

   public void modelDeleted(final String modeluri) {
      broadcastFullUpdate(modeluri, null);
   }

   public void modelSaved(final String modeluri) {
      broadcastDirtyState(modeluri, false);
   }

   private Stream<WsContext> getOpenSessions(final String modeluri) {
      return modelUrisToClients.getOrDefault(modeluri, Collections.emptySet()).stream()
         .filter(isOpenPredicate);
   }

   private Stream<WsContext> getOpenValidationSessions(final String modeluri) {
      return modelUrisToClientsValidation.getOrDefault(modeluri, Collections.emptySet()).stream()
         .filter(isOpenPredicate);
   }

   private void broadcastFullUpdate(final String modeluri, @Nullable final EObject updatedModel) {
      if (modelUrisToClients.containsKey(modeluri)) {
         getOpenSessions(modeluri)
            .forEach(session -> {
               try {
                  if (updatedModel == null) {
                     // model has been deleted
                     session.send(JsonResponse.fullUpdate(NullNode.getInstance()));
                  } else {
                     session.send(JsonResponse.fullUpdate(encoder.encode(session, updatedModel)));
                  }
               } catch (EncodingException e) {
                  LOG.error("Broadcast full update of " + modeluri + " failed", e);
               }
            });
      }
   }

   private void broadcastIncrementalUpdate(final String modeluri, final CCommand updatedModel) {
      if (modelUrisToClients.containsKey(modeluri)) {
         getOpenSessions(modeluri)
            .forEach(session -> {
               try {
                  session.send(JsonResponse.incrementalUpdate(encoder.encode(session, updatedModel)));
               } catch (EncodingException e) {
                  LOG.error("Broadcast incremental update of " + modeluri + " failed", e);
               }
            });
      }
   }

   private void broadcastDirtyState(final String modeluri, final Boolean isDirty) {
      getOpenSessions(modeluri)
         .forEach(session -> session.send(JsonResponse.dirtyState(isDirty)));
   }

   private void broadcastValidationResult(final String modeluri) {
      ObjectMapper mapper = EMFModule.setupDefaultMapper();
      BasicDiagnostic newResult = this.modelRepository.getValidationResult();
      Resource res = this.modelRepository.loadResource(modeluri).get();
      if (modelUrisToClientsValidation.containsKey(modeluri)) {
         getOpenValidationSessions(modeluri)
            .forEach(session -> {
               if (!modelUriToLastSendDiagnostic.containsKey(modeluri)) {
                  modelUriToLastSendDiagnostic.put(modeluri, newResult);
                  session.send(JsonResponse
                     .validationResult(mapper.valueToTree(this.modelRepository.diagnosticToJSON(newResult, res))));
               } else {
                  if (!modelUriToLastSendDiagnostic.get(modeluri).getChildren().equals(newResult.getChildren())) {
                     modelUriToLastSendDiagnostic.replace(modeluri, newResult);
                     session.send(JsonResponse
                        .validationResult(mapper.valueToTree(this.modelRepository.diagnosticToJSON(newResult, res))));
                  }
               }
            });
      }
   }

   private void broadcastError(final String modeluri, final String errorMessage) {
      getOpenSessions(modeluri)
         .forEach(session -> session.send(JsonResponse.error(errorMessage)));
   }

   boolean isClientSubscribed(final WsContext ctx) {
      return !modelUrisToClients.entrySet().stream().filter(entry -> entry.getValue().contains(ctx)).collect(toSet())
         .isEmpty();
   }

   @TestOnly
   void setIsOnlyPredicate(final Predicate<? super WsContext> isOpen) {
      this.isOpenPredicate = isOpen == null ? ctx -> ctx.session.isOpen() : isOpen;
   }
}
