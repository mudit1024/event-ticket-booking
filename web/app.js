const CATALOG = window.ETB_CONFIG?.catalogUrl || `http://${location.hostname}:8081`;
const BOOKING = window.ETB_CONFIG?.bookingUrl || `http://${location.hostname}:8083`;
const INVENTORY = window.ETB_CONFIG?.inventoryUrl || '/api/inventory';
const grid = document.querySelector('#event-grid');
const notice = document.querySelector('#notice');
const dialog = document.querySelector('#booking-dialog');
let selectedEvent = null;
let selectedTicket = null;
const palettes = [ ['#435a54','#b6c5a8'], ['#765052','#d89d79'], ['#354967','#6fa4a0'], ['#835938','#e4b34e'] ];
const money = (amount, currency='INR') => new Intl.NumberFormat('en-IN',{style:'currency',currency,maximumFractionDigits:0}).format(amount);
const date = value => new Intl.DateTimeFormat('en-IN',{weekday:'short',day:'numeric',month:'short',hour:'numeric',minute:'2-digit'}).format(new Date(value));
function showError(message){notice.hidden=false;notice.textContent=message;}
async function loadEvents(){
  try {
    const response=await fetch(`${CATALOG}/api/v1/events`);
    if(!response.ok) throw new Error(`Catalog service returned ${response.status}`);
    const events=await response.json();
    if(!events.length){grid.innerHTML='<div class="empty">No events are published yet. Start the catalog service and add an event to see it here.</div>';return;}
    grid.innerHTML=events.map((event,index)=>{
      const ticket=event.ticket_types?.find(item=>item.published);
      const [a,b]=palettes[index%palettes.length];
      return `<article class="event-card"><div class="event-image" style="background:radial-gradient(circle at 50% 48%,${b},${a} 72%)"><small>${event.location||'LIVE EVENT'}</small><span>${String(event.name||'Event').split(' ').slice(0,2).map(w=>w[0]).join('')}</span></div><div class="event-info"><h3>${escapeHtml(event.name)}</h3><div class="meta"><span>◷ ${date(event.starts_at)}</span><span>⌖ ${escapeHtml(event.location)}</span></div><div class="ticket-row">${ticket?`<div><strong>From ${money(ticket.price,ticket.currency)}</strong><br><small>${escapeHtml(ticket.name)}</small></div><button data-event="${index}">Get tickets ↗</button>`:'<small>Tickets unavailable</small>'}</div></div></article>`;
    }).join('');
    grid.querySelectorAll('[data-event]').forEach(button=>button.addEventListener('click',()=>openBooking(events[button.dataset.event])));
  } catch(error){grid.innerHTML='<div class="empty">Events aren’t loading right now.</div>';showError(`The browser could not load the event catalog at ${CATALOG}. Check that the catalog service is running and reachable. If DevTools reports a CORS error, allow this UI origin (${location.origin}) in the service, or set a same-origin proxy. Details: ${error.message}`);}
}
function escapeHtml(value=''){return String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function openBooking(event){selectedEvent=event;const tickets=event.ticket_types.filter(t=>t.published);document.querySelector('#modal-event').textContent=`${event.name} · ${date(event.starts_at)} · ${event.location}`;document.querySelector('#ticket').innerHTML=tickets.map((t,i)=>`<option value="${i}">${escapeHtml(t.name)} · ${money(t.price,t.currency)}</option>`).join('');selectedTicket=tickets[0];document.querySelector('#result').innerHTML='';document.querySelector('#quantity').value=1;dialog.showModal();updateTotal();}
function updateTotal(){const ticket=selectedEvent?.ticket_types.filter(t=>t.published)[document.querySelector('#ticket').value];if(ticket){selectedTicket=ticket;document.querySelector('#total').textContent=money(ticket.price*Number(document.querySelector('#quantity').value),ticket.currency);}}
document.querySelector('.close').onclick=()=>dialog.close();
document.querySelector('#ticket').onchange=updateTotal;
document.querySelector('#quantity').oninput=updateTotal;
document.querySelector('#minus').onclick=()=>{const q=document.querySelector('#quantity');q.value=Math.max(1,+q.value-1);updateTotal();};
document.querySelector('#plus').onclick=()=>{const q=document.querySelector('#quantity');q.value=Math.min(10,+q.value+1);updateTotal();};
document.querySelector('#booking-form').onsubmit=async e=>{e.preventDefault();const button=e.currentTarget.querySelector('.submit'), result=document.querySelector('#result');button.disabled=true;button.textContent='Securing your tickets…';result.className='';result.textContent='';
  try{const response=await fetch(`${BOOKING}/api/v1/bookings`,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({event_id:selectedEvent.event_id,ticket_type_id:selectedTicket.ticket_type_id,quantity:Number(document.querySelector('#quantity').value),customer_email:document.querySelector('#email').value})});const payload=await response.json();if(!response.ok)throw new Error(payload.message||payload.error||`Booking service returned ${response.status}`);result.innerHTML=`<strong>You're all set!</strong><br>Booking ${escapeHtml(payload.booking_id)} · ${escapeHtml(payload.status)}<br>${money(payload.total_amount,payload.currency)} charged for ${payload.quantity} ticket(s).`;result.className='result';}catch(error){result.textContent=`Booking could not be completed: ${error.message}`;result.className='result error';}finally{button.disabled=false;button.innerHTML='Confirm booking <span>↗</span>';}
};
loadEvents();

let currentReservation = null;
const reservationResult = document.querySelector('#reservation-result');
const releaseButton = document.querySelector('#release-button');
async function readJson(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || data.error || `Request failed (${response.status})`);
  return data;
}
function renderReservation(r) {
  currentReservation = r;
  reservationResult.className = 'reservation-result';
  reservationResult.innerHTML = `<div class="reservation-summary"><span class="status status-${escapeHtml(r.status).toLowerCase()}">${escapeHtml(r.status)}</span><strong>${escapeHtml(r.reservation_id)}</strong><span>${escapeHtml(r.quantity)} ticket(s)</span><small>Booking ${escapeHtml(r.booking_id)} · Updated ${date(r.updated_at)}</small></div>`;
  // Keep the action available for every found reservation. Inventory owns
  // the state rules and returns the appropriate conflict for confirmed ones.
  releaseButton.disabled = false;
}
document.querySelector('#lookup-form').addEventListener('submit', async e => {
  e.preventDefault();
  const id = document.querySelector('#reservation-id').value.trim();
  currentReservation = null; releaseButton.disabled = true;
  reservationResult.className = 'reservation-result'; reservationResult.textContent = 'Looking up reservation…';
  try {
    let reservation;
    try {
      reservation = await readJson(await fetch(`${INVENTORY}/api/v1/reservations/${encodeURIComponent(id)}`));
    } catch (inventoryError) {
      // If it's a booking ID, resolve its reservation ID through Booking.
      const booking = await readJson(await fetch(`${BOOKING}/api/v1/bookings/${encodeURIComponent(id)}`));
      if (!booking.reservation_id) throw new Error(`Booking ${booking.booking_id} has no reservation (status: ${booking.status}).`);
      reservation = await readJson(await fetch(`${INVENTORY}/api/v1/reservations/${encodeURIComponent(booking.reservation_id)}`));
    }
    renderReservation(reservation);
  } catch (error) {
    reservationResult.className = 'reservation-result error';
    reservationResult.textContent = `Could not find reservation: ${error.message}`;
  }
});
releaseButton.addEventListener('click', async () => {
  if (!currentReservation) return;
  if (!confirm('Release these tickets back to inventory? This cannot be undone.')) return;
  releaseButton.disabled = true; reservationResult.textContent = 'Releasing tickets…';
  try {
    const updated = await readJson(await fetch(`${INVENTORY}/api/v1/reservations/${encodeURIComponent(currentReservation.reservation_id)}/release`, {method:'POST'}));
    renderReservation(updated);
  } catch (error) {
    reservationResult.className = 'reservation-result error';
    reservationResult.textContent = `Could not release reservation: ${error.message}`;
    releaseButton.disabled = false;
  }
});
