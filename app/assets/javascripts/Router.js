/**
 * Router.js
 */

(function(PlayStory, RouterUtils) {

     PlayStory.Router = (function() {
         console.log("[PlayStory.Router] Init play story router");

         //Events
         var onRouteChange = function(next) {
             window.addEventListener('hashchange', next);
         };

         //Interactions
         var router = When(onRouteChange)
         .map(function(evt) {
            return evt.newURL.split('#')[1];
         });

         //Utils
         var currentRoute = function() {
             return window.location.hash.substr(1,window.location.hash.length);
         };

         //Actions
         var matchParams = function(routeAsRegex) {
             return Action(function(route, next) {
                 var params= RouterUtils.matchParams(currentRoute(), routeAsRegex);
                 next(params);
             });
         };

         var subscribe = function(route, actions) {
             var routeAsRegex = RouterUtils.routeAsRegex(route);

             actions.unshift(matchParams(routeAsRegex));
             var composedActions = actions.reduce(function(prevAction, currentAction) {
                 return prevAction.then(currentAction);
             });

             var r = Match.regex(routeAsRegex, matchParams(routeAsRegex).then(composedActions));
             router.match(r).subscribe();

             if(routeAsRegex.test(currentRoute())) {
                 var params = RouterUtils.matchParams(currentRoute(), routeAsRegex);
                 composedActions._do(params);
             }
         };

         return new (function() {
             var self = this,
                 route = null;

             this.when = function(specifiedRoute, action) {
                 route = specifiedRoute;
                 if(action) {
                     subscribe(specifiedRoute, [action]);
                     return null;
                 } else {
                     return self;
                 }
             };

             this.go = function(route) {
                 history.pushState({}, route, "#" + route);
             };

             this.goAsAction = function(uriPattern, buildURI) {
                 return Action(function(feed, next) {
                     var uri = buildURI(uriPattern, feed);
                     self.go(uri);
                     next(feed);
                 });
             };

             this.chain = function() {
                 var actions = [];
                 for(var index = 0; index<arguments.length; index++) {
                     actions.push(arguments[index]);
                 }
                 subscribe(route, actions);
                 route = route;

                 return {
                     and: function() {
                         var mergedActions = [];
                         for(var index = 0; index<arguments.length; index++) {
                             mergedActions.push(arguments[index]);
                         }
                         subscribe(route, mergedActions);
                         return this;
                     }
                 };
             };

             this.currentRoute = currentRoute;
         })();
     })();

 })(window.PlayStory || {}, window.RouterUtils || {});