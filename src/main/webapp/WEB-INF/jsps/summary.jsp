<%@ page language="java" contentType="text/html; charset=UTF-8" session="false" 
import="java.text.*,java.math.RoundingMode,com.ibm.hybrid.cloud.sample.stocktrader.trader.json.*"%>

<%!
static NumberFormat currency = NumberFormat.getNumberInstance();
static {

  currency.setMinimumFractionDigits(2);
  currency.setMaximumFractionDigits(2);
  currency.setRoundingMode(RoundingMode.HALF_UP);
} 
%>

<!DOCTYPE html >
<html lang="en">
  <head>
    <title>Stock Trader</title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
    <script>
      (function(s,t,a,n){s[t]||(s[t]=a,n=s[a]=function(){n.q.push(arguments)},
      n.q=[],n.v=2,n.l=1*new Date)})(window,"InstanaEumObject","ineum");

      ineum('reportingUrl', 'https://eum-orange-saas.instana.io');
      ineum('key', 'Ug33B-6oS1ebQGUCTJB86A');
      ineum('trackSessions');
    </script>
    <script defer crossorigin="anonymous" src="https://eum.instana.io/eum.min.js"></script>
  </head>
  <body>
    <img src="header.jpg" width="534" height="200" alt="header image"/>
    <br/>
    <br/>
    <%
      if(request.getAttribute("error") != null && ((Boolean)request.getAttribute("error")).booleanValue() == true) {
    %>
      Error communicating with the Broker microservice: ${message}
      <p/>
      Please consult the <i>trader</i>, <i>broker</i> and <i>portfolio</i> pod logs for more details, or ask your administator for help.
      <p/>
    <%
      } else {
    %>
    <form method="post"/>
    <input type="radio" name="action" value="retrieve" checked> Retrieve selected portfolio<br>

    <% if(request.isUserInRole("StockTrader")) { %>
      <input type="radio" name="action" value="create"> Create a new portfolio<br>
      <input type="radio" name="action" value="update"> Update selected portfolio (buy/sell stock)<br>
      <input type="radio" name="action" value="delete"> Delete selected portfolio<br>
    <% } %>

    <br/>
    <table border="1" cellpadding="5">
       <tr>
       <th scope="col"></th>
       <th scope="col">Owner</th>
       <th scope="col">Total</th>
       <th scope="col">Loyalty Level</th>
       </tr>
<%
Broker[] brokers = (Broker[])request.getAttribute("brokers");
for (int index=0; index<brokers.length; index++) { 
  Broker broker = brokers[index];
  %>
  <tr>
    <td><input type="radio" name="owner" value="<%=broker.getOwner()%>" <%= ((index ==0)?" checked ": " ") %>></td>
    <td><%=broker.getOwner()%></td>
    <td>$<%=currency.format(broker.getTotal())%></td>
    <td><%=broker.getLoyalty()%></td>
  </tr>
<% } %>
    </table>
    <br/>
    <input type="submit" name="submit" value="Submit" style="font-family: sans-serif; font-size: 16px;"/>
    <input type="submit" name="submit" value="Log Out" style="font-family: sans-serif; font-size: 16px;"/>
  </form>
    <%
      }

    %>

    <br/>
    <a href="https://github.com/IBMStockTrader">
      <img src="footer.jpg" alt="footer image"/>
    </a>
  </body>
</html>
