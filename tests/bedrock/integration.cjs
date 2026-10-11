const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const net = require('node:net')
const bedrock = require('bedrock-protocol')
const nbt = require('prismarine-nbt')
const settings = JSON.parse(fs.readFileSync(process.env.TEST_SETTINGS || path.resolve(__dirname, '../../.integration/test-settings.json')))
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const checks = []
const locale = process.env.TEST_LOCALE || "ja_JP"
const propertySchemas = []
const propertyPackets = []
const debug = (...args) => { if(process.env.TEST_DEBUG)console.log(...args) }
const state = { inventory: [], offhand: { network_id: 0 }, cursor: { network_id: 0 }, responses: [], closes: [], equipment: [], titles: [], animations: [], registry: [], blocks: [], position: {x:0,y:0,z:0}, spawned:false, closing:false }
let savedDifficulty
let client, rcon, inputTimer, requestId = 10, stackId = 1, tick = 0n
async function until(label, predicate, timeout=15000) {
  const end=Date.now()+timeout
  while (Date.now()<end) { if(state.error) throw state.error; if(await predicate()) return; await pause(100) }
  throw Error('Timeout: '+label)
}
async function connectRcon() {
  const socket=net.connect(settings.rconPort,'127.0.0.1')
  let buffer=Buffer.alloc(0);const waiting=new Map()
  socket.on('data',chunk=>{
    buffer=Buffer.concat([buffer,chunk])
    while(buffer.length>=4 && buffer.length>=buffer.readInt32LE(0)+4) {
      const size=buffer.readInt32LE(0),id=buffer.readInt32LE(4),type=buffer.readInt32LE(8),body=buffer.subarray(12,size+2).toString()
      buffer=buffer.subarray(size+4)
      if(waiting.has(id) && (type===0 || type===2)) { waiting.get(id)(body);waiting.delete(id) }
    }
  })
  await new Promise((resolve,reject)=>{socket.once('connect',resolve);socket.once('error',reject)})
  const send=(type,body)=>new Promise((resolve,reject)=>{
    const id=++requestId,payload=Buffer.from(body),packet=Buffer.alloc(payload.length+14)
    packet.writeInt32LE(payload.length+10,0);packet.writeInt32LE(id,4);packet.writeInt32LE(type,8);payload.copy(packet,12)
    const timer=setTimeout(()=>{waiting.delete(id);reject(Error('RCON timeout'))},10000)
    waiting.set(id,response=>{clearTimeout(timer);resolve(response)})
    socket.write(packet)
  })
  await send(3,settings.rconPassword)
  return {command:cmd=>send(2,cmd),close:()=>socket.end()}
}
// Native colored beds and banners share their identifier; damage/NBT carries color.
const definition = id => state.registry.find(i=>i.name==='nativeoffhand:'+id)
  || state.registry.find(i=>i.name==='minecraft:'+(id.endsWith('_bed') ? 'bed' : id.endsWith('_banner') ? 'banner' : id))
const uiDefinition = id => state.registry.find(i=>i.name==='nativeoffhand_ui:'+id)
const check = (label,evidence) => { checks.push({label,evidence});console.log('PASS',label,evidence||'') }
async function cmd(command) { const result=await rcon.command(command);if(/Unknown|Incorrect|No entity was found|No player was found/.test(result))throw Error(command+': '+result);return result }
async function inventoryData() { return cmd('data get entity NativeOH equipment') }
async function setOffhand(id,count=1) {
  await cmd('item replace entity NativeOH weapon.offhand with minecraft:'+id+' '+count)
  await until('offhand '+id,()=>id==='air' ? !state.offhand.network_id : state.offhand.network_id===definition(id)?.runtime_id && state.offhand.count===count)
}
async function setMain(id,count=1) {
  await cmd('item replace entity NativeOH hotbar.0 with minecraft:'+id+' '+count)
  await until('main '+id,()=>id==='air' ? !state.inventory[0]?.network_id : state.inventory[0]?.network_id===definition(id)?.runtime_id && state.inventory[0].count===count)
}
async function transferRoundTrip(id,count) {
  await setOffhand('air')
  await setMain(id,count)
  if(id==='stone') {
    await cmd('item replace entity NativeOH hotbar.0 with minecraft:stone[custom_name={text:"Movable named stone"}] '+count)
    await until('named stone before UI',()=>state.inventory[0]?.extra?.nbt
      && nbt.simplify(state.inventory[0].extra.nbt.nbt).display?.Name?.includes('Movable named stone'))
  }
  const original=state.inventory[0],ui=uiDefinition(id)
  state.opened=null
  client.queue('interact',{action_id:'open_inventory',target_entity_id:state.runtimeId,has_position:false})
  await until('inventory open',()=>state.opened)
  await until('inventory definition '+id,()=>state.inventory[0]?.network_id===(ui || definition(id)).runtime_id)
  const from=state.inventory[0],forward=stackId++
  assert.equal(from.stack_id,original.stack_id,id+' presentation must retain stack ID')
  assert.equal(from.count,count)
  if(id==='stone') assert.equal(nbt.simplify(from.extra.nbt.nbt).display.Name,
    nbt.simplify(original.extra.nbt.nbt).display.Name,'Custom name survives UI presentation')
  if(ui) {
    assert.equal(ui.version,'data_driven',id+' movable UI definition')
    assert.equal(nbt.simplify(ui.nbt).components.item_properties.allow_off_hand,1)
  }
  client.queue('item_stack_request',{requests:[{request_id:forward,actions:[{type_id:'place',count,
    source:{slot_type:{container_id:'hotbar'},slot:0,stack_id:from.stack_id},
    destination:{slot_type:{container_id:'offhand'},slot:1,stack_id:0}
  }],custom_names:[],cause:'slash_command_chat'}]})
  await until('offhand transfer '+id,()=>state.responses.flatMap(p=>p.responses).some(r=>r.request_id===forward && r.status==='ok'))
  const response=state.responses.flatMap(p=>p.responses).find(r=>r.request_id===forward)
  const moved=response.containers.find(c=>c.slot_type.container_id==='offhand').slots[0]
  assert.equal(moved.count,count)
  let equipped
  await until('Java OFFHAND confirms '+id,async()=>{
    equipped=await cmd('data get entity NativeOH equipment.offhand')
    return equipped.includes('minecraft:'+id) && equipped.includes('count: '+count)
  })
  if(id==='stone') assert(equipped.includes('Movable named stone'),'Java custom name remains after transfer')
  if(['stone','iron_axe','lantern','soul_lantern','soul_torch'].includes(id)) await checkHud(original,count,'HUD keeps native '+id+' AUX while the inventory uses a move alias')
  if(ui) assert(!nbt.simplify(definition(id).nbt).components?.['minecraft:block_placer'],id+' native renderer is untouched')
  state.offhand={...from,count,stack_id:moved.item_stack_id};state.inventory[0]={network_id:0}
  const backward=stackId++
  client.queue('item_stack_request',{requests:[{request_id:backward,actions:[{type_id:'place',count,
    source:{slot_type:{container_id:'offhand'},slot:1,stack_id:moved.item_stack_id},
    destination:{slot_type:{container_id:'hotbar'},slot:0,stack_id:0}
  }],custom_names:[],cause:'slash_command_chat'}]})
  await until('offhand return '+id,()=>state.responses.flatMap(p=>p.responses).some(r=>r.request_id===backward && r.status==='ok'))
  const returned=state.responses.flatMap(p=>p.responses).find(r=>r.request_id===backward).containers.find(c=>c.slot_type.container_id==='hotbar').slots[0]
  assert.equal(returned.count,count)
  state.inventory[0]={...from,count,stack_id:returned.item_stack_id};state.offhand={network_id:0}
  await until('Java hotbar confirms returned '+id,async()=>
    (await cmd('execute if items entity NativeOH hotbar.0 minecraft:'+id)).includes('Test passed'))
  const closeCount=state.closes.length
  client.queue('container_close',{window_id:state.opened.window_id,server:false})
  await until('inventory close confirmation '+id,()=>state.closes.length>closeCount)
  await until('native definition restored '+id,()=>state.inventory[0]?.network_id===original.network_id
    && state.inventory[0]?.count===count && (ui ? state.inventory[0]?.stack_id!==returned.item_stack_id : state.inventory[0]?.stack_id===returned.item_stack_id))
  assert.equal(state.inventory[0].block_runtime_id,original.block_runtime_id,id+' native block state')
  assert.equal(state.inventory[0].metadata,original.metadata,id+' native data value')
  if(id==='stone') assert.equal(nbt.simplify(state.inventory[0].extra.nbt.nbt).display.Name,
    nbt.simplify(original.extra.nbt.nbt).display.Name,'Custom name survives native restoration')
  check('Native inventory offhand round trip: '+id,equipped)
}
function inventorySlot(container,slot) {
  if(container==='offhand')return state.offhand
  if(container==='cursor')return state.cursor
  return state.inventory[slot] || {network_id:0}
}
function updateInventorySlot(container,slot,item) {
  if(container==='offhand')state.offhand=item
  else if(container==='cursor')state.cursor=item
  else state.inventory[slot]=item
}
function requestSlot(container,slot) {
  return {slot_type:{container_id:container},slot,stack_id:inventorySlot(container,slot).stack_id || 0}
}
async function nativeAction(type,count,sourceContainer,sourceSlot,destContainer,destSlot) {
  const source={...inventorySlot(sourceContainer,sourceSlot)},dest={...inventorySlot(destContainer,destSlot)}
  const action={type_id:type,source:requestSlot(sourceContainer,sourceSlot),destination:requestSlot(destContainer,destSlot)}
  if(type!=='swap')action.count=count
  const id=stackId++
  client.queue('item_stack_request',{requests:[{request_id:id,actions:[action],custom_names:[],cause:'slash_command_chat'}]})
  await until('native '+type+' response',()=>state.responses.flatMap(p=>p.responses).some(r=>r.request_id===id))
  const response=state.responses.flatMap(p=>p.responses).find(r=>r.request_id===id)
  assert.equal(response.status,'ok',JSON.stringify(response))
  if(type==='swap') {
    updateInventorySlot(sourceContainer,sourceSlot,dest)
    updateInventorySlot(destContainer,destSlot,source)
  } else {
    updateInventorySlot(sourceContainer,sourceSlot,source.count>count ? {...source,count:source.count-count} : {network_id:0})
    updateInventorySlot(destContainer,destSlot,{...(dest.network_id ? dest : source),count:(dest.count || 0)+count})
  }
  for(const container of response.containers) {
    for(const slot of container.slots) {
      const name=container.slot_type.container_id
      const old=inventorySlot(name,slot.slot)
      updateInventorySlot(name,slot.slot,slot.count ? {...old,count:slot.count,stack_id:slot.item_stack_id} : {network_id:0})
    }
  }
  return response
}
async function checkJavaCount(javaPath,count) {
  // Geyser acknowledges the predicted move before Java processes the click.
  await until('Java count confirms '+javaPath+' = '+count,async()=>
    (await cmd('data get entity NativeOH '+javaPath)).trim().endsWith(': '+count))
}
async function nativeInventoryOperations() {
  await cmd('clear NativeOH')
  await setMain('diamond_sword')
  await cmd('item replace entity NativeOH inventory.0 with minecraft:stone 16')
  await cmd('item replace entity NativeOH inventory.1 with minecraft:apple 4')
  await cmd('item replace entity NativeOH inventory.2 with minecraft:diamond_sword[damage=17,custom_name={text:"Named blade"},enchantments={"minecraft:sharpness":3}] 1')
  await until('ordinary inventory setup',()=>state.inventory[9]?.count===16 && state.inventory[10]?.count===4 && state.inventory[11]?.count===1)
  const blade={...state.inventory[11]}
  state.opened=null
  client.queue('interact',{action_id:'open_inventory',target_entity_id:state.runtimeId,has_position:false})
  await until('ordinary inventory aliases',()=>state.opened
    && state.inventory[9]?.network_id===(uiDefinition('stone') || definition('stone')).runtime_id
    && state.inventory[11]?.network_id===(uiDefinition('diamond_sword') || definition('diamond_sword')).runtime_id)
  await nativeAction('place',6,'inventory',9,'offhand',1)
  await checkJavaCount('equipment.offhand.count',6)
  await checkJavaCount('Inventory[{Slot:9b}].count',10)
  check('Ordinary inventory slots directly place part of a stack into OFFHAND')
  await nativeAction('place',4,'inventory',9,'offhand',1)
  await checkJavaCount('equipment.offhand.count',10)
  await checkJavaCount('Inventory[{Slot:9b}].count',6)
  check('Ordinary inventory merges a matching stack into occupied OFFHAND without duplication')
  await nativeAction('take',3,'offhand',1,'cursor',0)
  assert.equal(state.cursor.count,3)
  await nativeAction('place',3,'cursor',0,'inventory',9)
  assert(!state.cursor.network_id)
  await checkJavaCount('equipment.offhand.count',7)
  await checkJavaCount('Inventory[{Slot:9b}].count',9)
  check('Native cursor splits OFFHAND and returns items to an ordinary inventory slot')
  await nativeAction('swap',0,'inventory',10,'offhand',1)
  assert.equal(state.offhand.network_id,(uiDefinition('apple') || definition('apple')).runtime_id)
  await checkJavaCount('equipment.offhand.count',4)
  await checkJavaCount('Inventory[{Slot:10b}].count',7)
  assert((await cmd('execute if items entity NativeOH weapon.offhand minecraft:apple')).includes('Test passed'))
  check('Native inventory swaps food with a block already held in OFFHAND')
  await nativeAction('swap',0,'inventory',11,'offhand',1)
  assert((await cmd('execute if items entity NativeOH weapon.offhand minecraft:diamond_sword[damage=17,enchantments={"minecraft:sharpness":3}]')).includes('Test passed'))
  assert(nbt.simplify(state.offhand.extra.nbt.nbt).display.Name.includes('Named blade'))
  client.queue('container_close',{window_id:state.opened.window_id,server:false})
  await until('native named blade restoration',()=>state.offhand.network_id===blade.network_id && state.offhand.count===1)
  assert.equal(state.offhand.metadata,blade.metadata)
  assert.deepEqual(nbt.simplify(state.offhand.extra.nbt.nbt),nbt.simplify(blade.extra.nbt.nbt))
  check('OFFHAND inventory swap preserves sword damage, enchantment and custom name after closing')
  state.opened=null
  client.queue('interact',{action_id:'open_inventory',target_entity_id:state.runtimeId,has_position:false})
  await until('reopen named offhand item',()=>state.opened && state.offhand.network_id===(uiDefinition('diamond_sword') || definition('diamond_sword')).runtime_id)
  await nativeAction('take',1,'offhand',1,'cursor',0)
  await nativeAction('place',1,'cursor',0,'inventory',12)
  client.queue('container_close',{window_id:state.opened.window_id,server:false})
  await until('named blade returned to ordinary inventory',()=>!state.offhand.network_id && state.inventory[12]?.network_id===blade.network_id)
  assert.deepEqual(nbt.simplify(state.inventory[12].extra.nbt.nbt),nbt.simplify(blade.extra.nbt.nbt))
  await checkJavaCount('Inventory[{Slot:9b}].count',9)
  await checkJavaCount('Inventory[{Slot:10b}].count',7)
  await checkJavaCount('Inventory[{Slot:11b}].count',4)
  assert((await cmd('execute if items entity NativeOH inventory.3 minecraft:diamond_sword[damage=17,enchantments={"minecraft:sharpness":3}]')).includes('Test passed'))
  check('Reopening native inventory returns OFFHAND through cursor and preserves all remaining items')
}

function use(action,block={x:1,y:64,z:0},held=state.inventory[0]||{network_id:0},runtime=0) {
  client.queue('inventory_transaction',{transaction:{legacy:{legacy_request_id:0},transaction_type:'item_use',actions:[],transaction_data:{
    action_type:action,trigger_type:'player_input',block_position:block,face:1,hotbar_slot:0,
    held_item:held,player_pos:state.position,click_pos:{x:.5,y:1,z:.5},
    block_runtime_id:runtime,client_prediction:'failure'
  }}})
}
const plainTitle = packet => packet?.text?.replace(/§./g,'')
const latestHud = type => state.titles.findLast(p=>p.type===type)?.text
function nativeHudAux(item) {
  if(!item.network_id) return 0
  const def=state.registry.find(d=>d.runtime_id===item.network_id)
  const apple=state.registry.find(d=>d.name==='minecraft:apple')
  const shrub=state.registry.find(d=>d.name==='minecraft:red_shrub')
  let id=item.network_id
  if(apple?.runtime_id===882 && apple.version==='data_driven' && shrub?.runtime_id===852
      && def?.name.startsWith('minecraft:') && def.version==='data_driven' && id>=852 && id<=883) {
    const customCount=state.registry.filter(d=>d.runtime_id>0 && d.version==='data_driven' && !d.name.startsWith('minecraft:')).length
    id+=Math.min(customCount,1569)
  }
  return (id << 16) | ((item.metadata || 0) & 0xffff)
}
async function checkHud(item,count,label) {
  const definition=state.registry.find(d=>d.runtime_id===item.network_id)
  if(item.network_id) {
    assert(definition,'HUD item exists in the client registry')
    assert(!definition.name.startsWith('nativeoffhand_ui:'),'HUD uses the native item, not the inventory move alias')
  }
  const itemAux=String(nativeHudAux(item))
  const countText=count>1 ? String(count) : ''
  await until('offhand HUD '+label,()=>latestHud('set_title')===itemAux && latestHud('set_subtitle')===countText)
  check(label,{identifier:definition?.name || 'minecraft:air',itemAux,countText})
}
async function hudOperations() {
  await cmd('title NativeOH reset')
  await setOffhand('stone',64)
  await checkHud(state.offhand,64,'Native HUD receives block AUX and stack count')
  await setOffhand('bread',7)
  await checkHud(state.offhand,7,'Native HUD replaces the block AUX with food')
  await setOffhand('red_wool',9)
  await checkHud(state.offhand,9,'Native HUD preserves a colored block data value')
  for(const [id,aux] of [['lantern',-13631488],['soul_lantern',-17629184],['soul_torch',-17563648]]) {
    await setOffhand(id,16)
    assert.equal(nativeHudAux(state.offhand),aux,'Known 26.50 native AUX for '+id)
    await checkHud(state.offhand,16,'Native HUD sends signed AUX for '+id)
  }
  await setOffhand('apple',2)
  await checkHud(state.offhand,2,'Native HUD corrects the local UI ID for data-driven apple')
  assert.equal(nativeHudAux(state.offhand),160628736,'Apple occupies local slot 2451 after custom-item shift saturation')
  await setOffhand('bread',1)
  await checkHud(state.offhand,1,'One item sends empty subtitle to hide the count')
  await setOffhand('air')
  await checkHud(state.offhand,0,'Empty OFFHAND sends zero AUX to hide the slot')
  await setOffhand('iron_axe',1)
  await checkHud(state.offhand,1,'Native HUD tool AUX leaves native equipment unchanged')
  const still=state.titles.length
  await pause(1500)
  assert(state.titles.slice(still).some(p=>p.type==='set_title' && p.text===String(nativeHudAux(state.offhand))))
  check('Unchanged HUD data refreshes before the live title binding expires')

  const noticeStart=state.titles.length
  await cmd('title NativeOH times 4 12 4')
  await cmd('title NativeOH subtitle {"text":"Reserved subtitle test"}')
  await cmd('title NativeOH title {"text":"Reserved title test"}')
  await setOffhand('stone',3)
  await cmd('title NativeOH actionbar {"text":"HUD actionbar preserved"}')
  await until('ordinary actionbar',()=>state.titles.slice(noticeStart).some(p=>plainTitle(p)==='HUD actionbar preserved'))
  await pause(300)
  assert(!state.titles.slice(noticeStart).some(p=>plainTitle(p)==='Reserved title test' || plainTitle(p)==='Reserved subtitle test'),
    'The HUD title channels are reserved for HUD data')
  check('Server title/subtitle cannot replace the native HUD data; actionbar is preserved')
  await cmd('title NativeOH reset')
  await checkHud(state.offhand,3,'Server title reset refreshes rather than clearing the native HUD slot')
}

async function main() {
  rcon=await connectRcon()
  savedDifficulty=(await cmd('difficulty')).match(/\b(peaceful|easy|normal|hard)\b/i)?.[1]?.toLowerCase()
  assert(savedDifficulty,'Capture difficulty before using the isolated test server')
  await cmd('whitelist off')
  client=bedrock.createClient({host:'127.0.0.1',port:settings.bedrockPort,version:'1.26.51',username:'NativeOH',offline:true,skinData:{LanguageCode:locale},transport:'raknet',conLog:()=>{}})
  client.prependOnceListener('session',profile=>{profile.xuid='31001'})
  client.on('error',err=>{state.error=err})
  client.on('disconnect',p=>{if(!state.closing)state.error=Error(JSON.stringify(p))})
  client.on('start_game',p=>{state.runtimeId=p.runtime_entity_id;state.position=p.player_position})
  client.on('item_registry',p=>{state.registry=p.itemstates})
  client.on('inventory_content',p=>{
    debug('DEBUG_CONTENT',p.window_id,JSON.stringify(p.input.slice(0,2)))
    if(p.window_id===0||p.window_id==='inventory')state.inventory=p.input
    if(p.window_id===119||p.window_id==='offhand')state.offhand=p.input[0]
  })
  client.on('inventory_slot',p=>{
    debug('DEBUG_SLOT',p.window_id,p.slot,JSON.stringify(p.item))
    if(p.window_id===0||p.window_id==='inventory')state.inventory[p.slot]=p.item
    if(p.window_id===119||p.window_id==='offhand')state.offhand=p.item
    if((p.window_id===124||p.window_id==='ui') && p.slot===0)state.cursor=p.item
  })
  client.on('item_stack_response',p=>{state.responses.push(p);debug('DEBUG_STACK',JSON.stringify(p))})
  client.on('update_block',p=>state.blocks.push(p))
  client.on('sync_entity_property',p=>propertySchemas.push(nbt.simplify(p.nbt)))
  client.on('set_entity_data',p=>propertyPackets.push(p))
  client.on('animate_entity',p=>state.animations.push(p))
  client.on('set_title',p=>{state.titles.push({...p,received:Date.now()});debug('DEBUG_TITLE',p)})
  client.on('mob_equipment',p=>{if(String(p.runtime_entity_id)===String(state.runtimeId))state.equipment.push(p)})
  client.on('container_close',p=>state.closes.push(p))
  client.on('container_open',p=>{state.opened=p;debug('OPENED',p)})
  client.on('move_player',p=>{
    debug('DEBUG_MOVE',JSON.stringify(p,(_,v)=>typeof v==='bigint'?v.toString():v))
    if(String(p.runtime_id)===String(state.runtimeId) || String(p.runtime_entity_id)===String(state.runtimeId))state.position=p.position
  })
  client.on('spawn',()=>{
    client.queue('request_chunk_radius',{chunk_radius:4,max_radius:4})
    client.queue('respawn',{position:state.position,state:2,runtime_entity_id:state.runtimeId})
    state.spawned=true
    inputTimer=setInterval(()=>client.queue('player_auth_input',{
      pitch:state.pitch||0,yaw:state.yaw||0,head_yaw:state.yaw||0,position:state.position,move_vector:{x:0,z:0},
      input_data:{handled_teleport:true,vertical_collision:true},input_mode:'mouse',play_mode:'normal',
      interaction_model:'crosshair',interact_rotation:{x:state.pitch||0,z:state.yaw||0},tick:++tick,
      delta:{x:0,y:0,z:0},analogue_move_vector:{x:0,z:0},camera_orientation:{x:0,y:0,z:1},raw_move_vector:{x:0,z:0}
    }),50)
  })
  await until('spawn',()=>state.spawned && state.registry.length,60000)
  check('Bedrock 1.26.51 joined actual Paper/Geyser server')
  if(process.env.TEST_HUD_ONLY==='1') {
    await hudOperations()
    for(const [id,count] of [['stone',4],['iron_axe',1],['diamond_sword',1],['lantern',16],['soul_lantern',16],['soul_torch',16]])
      await transferRoundTrip(id,count)
    const version=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../resource-pack/manifest.json'))).header.version
    const output=process.env.TEST_RESULT || path.resolve(__dirname,'../../.integration/results.json')
    fs.writeFileSync(output,JSON.stringify({timestamp:new Date().toISOString(),bedrockVersion:'1.26.51',resourcePackVersion:version,checks,totalChecks:checks.length,customDataDrivenItems:state.registry.filter(d=>d.runtime_id>0 && d.version==='data_driven' && !d.name.startsWith('minecraft:')).length,limitations:['Headless checks do not execute retail JSON UI, signed-string conversion or icon rendering.']},null,2)+'\n')
    return
  }
  const uiItems=state.registry.filter(i=>i.name.startsWith('nativeoffhand_ui:'))
  assert(uiItems.length>1500,'Automatic aliases cover ordinary blocks, tools and items')
  for(const item of state.registry.filter(i=>i.version==='data_driven')) {
    assert.equal(item.component_based,true,item.name)
    assert.equal(nbt.simplify(item.nbt).components.item_properties.allow_off_hand,1,item.name)
  }
  check('Data-driven native items and inventory-only aliases permit offhand',uiItems.length)
  // Inventory aliases also appear in the held view while the native inventory
  // is open. Their tool flag must not fall back to the ordinary-item pose.
  const handheldAliases = ['diamond_sword','iron_axe','diamond_pickaxe','iron_shovel','golden_hoe',
    'mace','trident','bow','crossbow','fishing_rod','carrot_on_a_stick','warped_fungus_on_a_stick','brush']
  for (const id of handheldAliases) {
    const alias=uiDefinition(id)
    assert(alias,'Missing tool alias '+id)
    assert.equal(nbt.simplify(alias.nbt).components.item_properties.hand_equipped,1,id+' must retain the handheld pose in the inventory')
  }
  for (const id of ['bread','ender_pearl','stone','stone_bricks','torch','water_bucket']) {
    const alias=uiDefinition(id)
    assert(alias,'Missing ordinary-item alias '+id)
    assert.equal(nbt.simplify(alias.nbt).components.item_properties.hand_equipped,0,id+' must retain the ordinary-item pose')
  }
  check('Inventory tool aliases retain handheld rendering; ordinary items and blocks keep their own pose', {handheld:handheldAliases.length,ordinary:6})
  assert(!state.registry.some(i=>i.name.startsWith('nativeoffhand:')),'No custom held item definitions')
  assert(!propertySchemas.some(s=>s.type==='minecraft:player' && s.properties.some(p=>p.name.startsWith('nativeoffhand:'))),'No custom player pose or consume properties')
  check('Rewrite registers no custom held items or player pose/consume properties')

  await cmd('execute as @e[type=minecraft:ender_pearl] at @s on owner if entity @s[name=NativeOH] run kill @e[type=minecraft:ender_pearl,distance=..0.01,limit=1]')
  await cmd('clear NativeOH')
  await cmd('gamemode survival NativeOH')
  await cmd('effect clear NativeOH')
  await cmd('effect give NativeOH minecraft:instant_health 1 5 true')
  // Pearl-spawned endermites can otherwise kill the headless stationary bot.
  // Protect only this test player, retaining survival item consumption.
  await cmd('effect give NativeOH minecraft:resistance 180 255 true')
  await cmd('fill -5 64 -5 5 64 5 minecraft:stone')
  await cmd('fill -5 65 -5 5 68 5 minecraft:air')
  await cmd('tp NativeOH 0.5 65 0.5 0 0')
  await until('teleport position',()=>Math.abs(state.position.x-.5)<.01 && Math.abs(state.position.y-66.62)<.02)
  // Send an actual movement update so Geyser acknowledges Paper's teleport.
  state.position={...state.position,z:state.position.z+0.0001}
  await pause(500)
  debug('DEBUG_POSITION',state.position)
  assert(!state.registry.some(i=>i.name==='nativeoffhand:shield'))
  assert(state.registry.some(i=>i.name==='minecraft:shield'))
  check('Shield keeps its vanilla identifier for native rendering')
  await setMain('stone',2)
  assert.equal(definition('stone').name,'minecraft:stone')
  assert(!state.registry.some(i=>i.name==='nativeoffhand:stone'))
  assert(state.inventory[0].block_runtime_id>0,JSON.stringify(state.inventory[0]))
  assert.equal(definition('stone').version,'none','Stone retains its native item version')
  assert(!nbt.simplify(definition('stone').nbt).components?.['minecraft:block_placer'],'Native stone renderer must not be replaced with a data-driven block placer')
  check('Stone retains ItemVersion.NONE, original ID and block state outside the inventory')

  const stoneName=nbt.simplify(state.inventory[0].extra.nbt.nbt).display.Name
  assert(stoneName.includes(locale.toLowerCase()==='ja_jp' ? '石' : 'Stone'),stoneName)
  assert(!stoneName.includes('tile.stone'),stoneName)
  check('Stone display name is localized for the client',stoneName)
  await cmd('item replace entity NativeOH hotbar.0 with minecraft:stone[custom_name={text:"Named stone"}] 2')
  await until('custom name preserved',()=>state.inventory[0]?.extra?.nbt && nbt.simplify(state.inventory[0].extra.nbt.nbt).display?.Name?.includes('Named stone'))
  check('A user custom item name retains priority over the localized fallback')
  await setMain('torch',8)
  await setOffhand('air')
  client.queue('interact',{action_id:'open_inventory',target_entity_id:state.runtimeId,has_position:false})
  await until('native inventory open',()=>state.opened)
  const from=state.inventory[0]
  client.queue('item_stack_request',{requests:[{request_id:stackId++,actions:[{type_id:'place',count:8,
    source:{slot_type:{container_id:'hotbar'},slot:0,stack_id:from.stack_id},
    destination:{slot_type:{container_id:'offhand'},slot:1,stack_id:0}
  }],custom_names:[],cause:'slash_command_chat'}]})
  await until('torch inventory response',()=>state.responses.some(p=>p.responses.some(r=>r.request_id===1 && r.status==='ok')))
  const response=state.responses.flatMap(p=>p.responses).find(r=>r.request_id===1)
  const moved=response.containers.find(c=>c.slot_type.container_id==='offhand').slots[0]
  state.offhand={...from,count:moved.count,stack_id:moved.item_stack_id}
  state.inventory[0]={network_id:0}
  assert.equal(moved.count,8)
  debug('STACK_RESPONSE',JSON.stringify(state.responses))
  let movedData
  await until('Java confirms initial torch transfer',async()=>{
    movedData=await cmd('data get entity NativeOH equipment.offhand')
    return movedData.includes('minecraft:torch') && movedData.includes('count: 8')
  })
  check('Native ItemStackRequest moved torches from hotbar into OFFHAND',movedData)
  client.queue('container_close',{window_id:state.opened.window_id,server:false})
  await pause(300)
  const b={x:1,y:64,z:0}
  use('click_block',{x:1000,y:64,z:0})
  await pause(350)
  assert.equal(state.offhand.count,8)
  assert((await inventoryData()).includes('count: 8'))
  check('Out-of-range offhand interaction is rejected without consumption')
  const forged={...from,network_id:state.registry.find(i=>i.name==='minecraft:stone').runtime_id}
  use('click_block',b,forged)
  await pause(350)
  assert.equal(state.offhand.count,8)
  assert((await inventoryData()).includes('count: 8'))
  check('Forged held definition is rejected without consumption')
  debug('BEFORE_USE',await cmd('data get entity NativeOH Pos'),state.position)
  use('click_block',b)
  await until('torch consumed',()=>state.offhand.count===7)
  const placed=await cmd('execute if block 1 65 0 minecraft:torch')
  assert(placed.includes('Test passed'),placed)
  check('Empty main hand places exactly one native offhand torch',await inventoryData())
  await setMain('diamond_sword')
  await cmd('setblock 2 65 0 air')
  use('click_block',{x:2,y:64,z:0})
  await until('sword offhand torch consumed',()=>state.offhand.count===6)
  check('Sword in main hand allows offhand torch placement',await cmd('execute if block 2 65 0 minecraft:torch'))
  await setMain('stone',8)
  await cmd('setblock 1 65 1 air')
  use('click_block',{x:1,y:64,z:1})
  await until('main stone consumed',()=>state.inventory[0].count===7)
  assert.equal(state.offhand.count,6)
  check('Useful main hand retains priority; no offhand duplicate',await inventoryData())
  await setMain('air')
  await setOffhand('water_bucket',1)
  state.yaw=90;state.pitch=Math.atan2(1.62,3)*180/Math.PI
  await pause(200)
  use('click_block',{x:-3,y:64,z:0})
  await until('water bucket replaced',()=>state.offhand.network_id===definition('bucket')?.runtime_id)
  assert((await cmd('execute if block -3 65 0 minecraft:water')).includes('Test passed'))
  check('Native offhand water bucket places source water and returns empty bucket',await inventoryData())
  state.yaw=0;state.pitch=80
  await pause(200)
  await setOffhand('ender_pearl',3)
  use('click_air')
  await until('offhand pearl consumed',()=>state.offhand.count===2)
  check('Native offhand air-use consumes one ender pearl',await inventoryData())
  await pause(1500)
  state.pitch=0
  await cmd('tp NativeOH 0.5 65 0.5')
  await until('return from pearl',()=>Math.abs(state.position.x-.5)<.01 && Math.abs(state.position.y-66.62)<.02)
  state.position={...state.position,z:state.position.z+0.0001}
  await pause(300)
  await setMain('diamond_sword')
  await setOffhand('potion',1)
  use('click_air')
  await until('offhand potion consumed',()=>state.offhand.network_id===definition('glass_bottle')?.runtime_id,10000)
  check('Native offhand potion completes timed use',await inventoryData())
  await cmd('difficulty easy')
  await setOffhand('apple',2)
  await cmd('effect give NativeOH minecraft:hunger 15 255 true')
  await pause(4500)
  let hunger,foodLevel=20
  const hungerDeadline=Date.now()+12000
  do {
    hunger=await cmd('data get entity NativeOH foodLevel')
    foodLevel=Number(hunger.match(/: (\d+)$/)?.[1])
    if(foodLevel<20) break
    await pause(100)
  } while(Date.now()<hungerDeadline)
  assert(foodLevel<20,'Apple consumption needs a hungry player: '+hunger)
  debug('FOOD_LEVEL',hunger)
  // Match a held use input. The router must suppress repeated starts while
  // Java is already consuming, so these packets must not reset the timer.
  const foodInput=setInterval(()=>use('click_air'),150)
  try {
    use('click_air')
    await until('apple consumption with held use input',()=>state.offhand.count===1,10000)
  } finally { clearInterval(foodInput) }
  check('Native offhand food completes timed use',await inventoryData())
  assert(!state.animations.some(p=>p.animation.startsWith('animation.nativeoffhand.')))
  check('Offhand food and potion use emit no custom PlayAnimation')
  await setMain('diamond_sword')
  await setOffhand('stone',4)
  await cmd('setblock -1 65 1 minecraft:air')
  use('click_block',{x:-1,y:64,z:1})
  await until('post-food offhand stone placed',()=>state.offhand.count===3)
  assert((await cmd('execute if block -1 65 1 minecraft:stone')).includes('Test passed'))
  check('After eating, a sword in main hand allows native offhand stone placement',await inventoryData())
  await setMain('stone',4)
  await setOffhand('apple',1)
  await cmd('setblock -2 65 1 minecraft:air')
  use('click_block',{x:-2,y:64,z:1})
  await until('post-food main stone placed',()=>state.inventory[0].count===3)
  assert.equal(state.offhand.count,1)
  assert((await cmd('execute if block -2 65 1 minecraft:stone')).includes('Test passed'))
  check('After eating, main-hand stone placement keeps priority over offhand food')
  for(const [id,count] of [['shield',1],['lantern',16],['soul_lantern',16],['soul_torch',16],['stone',16],['stone_bricks',16],['diamond_sword',1],['iron_axe',1],['apple',4],['bread',4],['water_bucket',1],['written_book',1],['oak_planks',64],['red_wool',64],['glass',64],['oak_fence',64],['oak_stairs',64],['chest',64],['white_shulker_box',1],['red_bed',1],['red_banner',16],['oak_sign',16],['flower_pot',64]]) await transferRoundTrip(id,count)
  // Leave a transferred block in OFFHAND while closing, then place it and
  // reopen the inventory. This exercises both representations of the same
  // authoritative Java stack without relying on manually filled equipment.
  for(const id of ['stone','oak_stairs']) {
    await setOffhand('air')
    await setMain(id,3)
    const original=state.inventory[0],ui=uiDefinition(id)
    state.opened=null
    client.queue('interact',{action_id:'open_inventory',target_entity_id:state.runtimeId,has_position:false})
    await until('block UI open '+id,()=>state.opened && state.inventory[0]?.network_id===ui.runtime_id)
    const from=state.inventory[0],forward=stackId++
    client.queue('item_stack_request',{requests:[{request_id:forward,actions:[{type_id:'place',count:3,
      source:{slot_type:{container_id:'hotbar'},slot:0,stack_id:from.stack_id},
      destination:{slot_type:{container_id:'offhand'},slot:1,stack_id:0}
    }],custom_names:[],cause:'slash_command_chat'}]})
    await until('block equipped '+id,()=>state.responses.flatMap(p=>p.responses).some(r=>r.request_id===forward && r.status==='ok'))
    const moved=state.responses.flatMap(p=>p.responses).find(r=>r.request_id===forward).containers.find(c=>c.slot_type.container_id==='offhand').slots[0]
    const equipmentStart=state.equipment.length
    client.queue('container_close',{window_id:state.opened.window_id,server:false})
    await until('offhand native restoration '+id,()=>state.offhand.network_id===original.network_id && state.offhand.count===3)
    assert.notEqual(state.offhand.stack_id,moved.item_stack_id,id+' close refreshes protocol render identity')
    assert.equal(state.offhand.block_runtime_id,original.block_runtime_id)
    assert.equal(state.offhand.metadata,original.metadata)
    check('Closing inventory restores native OFFHAND block '+id+' with preserved state and fresh render identity')
    await until('native equipment refresh '+id,()=>state.equipment.slice(equipmentStart)
      .some(p=>(p.window_id===119 || p.window_id==='offhand') && p.item.network_id===original.network_id))
    const offPackets=state.equipment.slice(equipmentStart).filter(p=>p.window_id===119 || p.window_id==='offhand')
    assert(offPackets.some(p=>!p.item.network_id),'Offhand render is cleared first')
    assert.equal(offPackets.at(-1).item.network_id,original.network_id)
    assert.equal(offPackets.at(-1).item.block_runtime_id,original.block_runtime_id)
    check('OFFHAND equipment refresh sends AIR then native '+id+' with its block state')
    await setMain('diamond_sword')
    await cmd('setblock 3 65 1 minecraft:air')
    use('click_block',{x:3,y:64,z:1})
    await until('transferred native block placed '+id,()=>state.offhand.count===2)
    assert((await cmd('execute if block 3 65 1 minecraft:'+id)).includes('Test passed'))
    check('Inventory-transferred OFFHAND '+id+' places once after native restoration')
    const nativeHeld=state.offhand
    state.opened=null
    client.queue('interact',{action_id:'open_inventory',target_entity_id:state.runtimeId,has_position:false})
    await until('offhand UI reopen '+id,()=>state.opened && state.offhand.network_id===ui.runtime_id && state.offhand.count===2)
    assert.equal(state.offhand.stack_id,nativeHeld.stack_id)
    const backward=stackId++
    client.queue('item_stack_request',{requests:[{request_id:backward,actions:[{type_id:'place',count:2,
      source:{slot_type:{container_id:'offhand'},slot:1,stack_id:state.offhand.stack_id},
      destination:{slot_type:{container_id:'hotbar'},slot:1,stack_id:0}
    }],custom_names:[],cause:'slash_command_chat'}]})
    await until('reopened block returned '+id,()=>state.responses.flatMap(p=>p.responses).some(r=>r.request_id===backward && r.status==='ok'))
    client.queue('container_close',{window_id:state.opened.window_id,server:false})
    await until('returned native block '+id,()=>!state.offhand.network_id && state.inventory[1]?.network_id===original.network_id && state.inventory[1]?.count===2)
    assert((await cmd('execute if items entity NativeOH hotbar.1 minecraft:'+id)).includes('Test passed'))
    check('Reopening inventory restores movable '+id+' and returns remaining count')
    await cmd('item replace entity NativeOH hotbar.1 with minecraft:air')
  }
  await nativeInventoryOperations()
  await hudOperations()
  const output=process.env.TEST_RESULT || path.resolve(__dirname,'../../.integration/results.json')
  fs.writeFileSync(output,JSON.stringify({timestamp:new Date().toISOString(),bedrockVersion:'1.26.51',locale,checks,limitations:['Headless packet tests do not render or automate the Bedrock inventory UI.','Retail inventory drag restrictions and held-item appearance require a retail client test.']},null,2)+'\n')
}
main().catch(error=>{console.error(error);process.exitCode=1}).finally(async()=>{
  state.closing=true
  if(inputTimer)clearInterval(inputTimer)
  if(client)client.close()
  if(rcon && savedDifficulty) {
    try { await cmd('difficulty '+savedDifficulty);console.log('Restored test-server difficulty:',savedDifficulty) }
    catch(error) { console.error('Could not restore test-server difficulty:',error);process.exitCode=1 }
  }
  if(rcon)rcon.close()
  await pause(500)
})
