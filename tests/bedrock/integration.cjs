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
const state = { inventory: [], offhand: { network_id: 0 }, responses: [], animations: [], registry: [], blocks: [], position: {x:0,y:0,z:0}, spawned:false, closing:false }
let client, rcon, inputTimer, requestId = 10, stackId = 1, tick = 0n
async function until(label, predicate, timeout=15000) {
  const end=Date.now()+timeout
  while (Date.now()<end) { if(state.error) throw state.error; if(predicate()) return; await pause(100) }
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
const definition = id => state.registry.find(i=>i.name==='nativeoffhand:'+id) || state.registry.find(i=>i.name==='minecraft:'+id)
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
  state.opened=null
  client.queue('interact',{action_id:'open_inventory',target_entity_id:state.runtimeId,has_position:false})
  await until('inventory open',()=>state.opened)
  await pause(100)
  const from=state.inventory[0],forward=stackId++
  client.queue('item_stack_request',{requests:[{request_id:forward,actions:[{type_id:'place',count,
    source:{slot_type:{container_id:'hotbar'},slot:0,stack_id:from.stack_id},
    destination:{slot_type:{container_id:'offhand'},slot:1,stack_id:0}
  }],custom_names:[],cause:'slash_command_chat'}]})
  await until('offhand transfer '+id,()=>state.responses.flatMap(p=>p.responses).some(r=>r.request_id===forward && r.status==='ok'))
  const response=state.responses.flatMap(p=>p.responses).find(r=>r.request_id===forward)
  const moved=response.containers.find(c=>c.slot_type.container_id==='offhand').slots[0]
  assert.equal(moved.count,count)
  const equipped=await inventoryData()
  assert(equipped.includes('minecraft:'+id) && equipped.includes('count: '+count),equipped)
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
  assert((await cmd('execute if items entity NativeOH hotbar.0 minecraft:'+id)).includes('Test passed'))
  client.queue('container_close',{window_id:state.opened.window_id,server:false})
  await pause(150)
  check('Native inventory offhand round trip: '+id,equipped)
}
function use(action,block={x:1,y:64,z:0},held=state.inventory[0]||{network_id:0},runtime=0) {
  client.queue('inventory_transaction',{transaction:{legacy:{legacy_request_id:0},transaction_type:'item_use',actions:[],transaction_data:{
    action_type:action,trigger_type:'player_input',block_position:block,face:1,hotbar_slot:0,
    held_item:held,player_pos:state.position,click_pos:{x:.5,y:1,z:.5},
    block_runtime_id:runtime,client_prediction:'failure'
  }}})
}
async function main() {
  rcon=await connectRcon()
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
  })
  client.on('item_stack_response',p=>{state.responses.push(p);debug('DEBUG_STACK',JSON.stringify(p))})
  client.on('update_block',p=>state.blocks.push(p))
  client.on('sync_entity_property',p=>propertySchemas.push(nbt.simplify(p.nbt)))
  client.on('set_entity_data',p=>propertyPackets.push(p))
  client.on('animate_entity',p=>state.animations.push(p))
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
  for(const item of state.registry.filter(i=>i.runtime_id!==0)) {
    assert.equal(item.component_based,true,item.name)
    const data=nbt.simplify(item.nbt)
    assert.equal(data.components.item_properties.allow_off_hand,1,item.name)
  }
  check('All transmitted non-air definitions permit offhand',state.registry.filter(i=>i.runtime_id!==0).length)
  for(const id of ['stone','ender_pearl','diamond_sword']) {
    const components=nbt.simplify(definition(id).nbt).components
    assert(components.item_tags.includes('nativeoffhand:vanilla_offhand_pose'),id)
    assert(!components['minecraft:render_offsets'],id)
  }
  check('Ordinary offhand items select the mirrored vanilla arm pose without legacy offsets')
  for(const id of ['torch','soul_torch','redstone_torch','apple','bread','carrot','shield']) {
    const components=nbt.simplify(definition(id).nbt).components
    assert(!(components.item_tags || []).includes('nativeoffhand:vanilla_offhand_pose'),id)
    assert(!components['minecraft:render_offsets'],id)
  }
  check('Vanilla arm-pose correction excludes torches, food attachables and the native shield')
  await cmd('execute as @e[type=minecraft:ender_pearl] at @s on owner if entity @s[name=NativeOH] run kill @e[type=minecraft:ender_pearl,distance=..0.01,limit=1]')
  await cmd('clear NativeOH')
  await cmd('gamemode survival NativeOH')
  await cmd('effect clear NativeOH')
  await cmd('effect give NativeOH minecraft:instant_health 1 5 true')
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
  const movedData=await inventoryData()
  assert(movedData.includes('minecraft:torch') && movedData.includes('count: 8'),movedData)
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
  await setOffhand('apple',2)
  await cmd('effect give NativeOH minecraft:hunger 8 255 true')
  await pause(4500)
  const hunger=await cmd('data get entity NativeOH foodLevel')
  debug('FOOD_LEVEL',hunger)
  use('click_air')
  await until('apple consumption',()=>state.offhand.count===1,10000)
  check('Native offhand food completes timed use',await inventoryData())
  assert(!state.animations.some(p=>p.animation.startsWith('animation.nativeoffhand.')))
  check('Offhand food and potion use emit no custom PlayAnimation')
  const schema=propertySchemas.find(s=>s.type==='minecraft:player' && s.properties.some(p=>p.name==='nativeoffhand:consume_mode'))
  assert(schema, 'Missing attachable consumption property definition')
  const modeIndex=schema.properties.findIndex(p=>p.name==='nativeoffhand:consume_mode')
  const modes=propertyPackets.filter(p=>String(p.runtime_entity_id)===String(state.runtimeId))
    .flatMap(p=>p.properties.ints.filter(v=>v.index===modeIndex).map(v=>v.value))
  let cursor=0
  for(const mode of modes) if(mode===[2,0,1,0][cursor])cursor++
  assert.equal(cursor,4,JSON.stringify(modes))
  check('Attachable use state follows drink/idle/eat/idle while the main hand holds a sword',modes)
  const latestMode=propertyPackets.filter(p=>String(p.runtime_entity_id)===String(state.runtimeId))
    .flatMap(p=>p.properties.ints.filter(v=>v.index===modeIndex).map(v=>v.value)).at(-1)
  assert.equal(latestMode,0)
  check('Consumption animation state resets before selecting a normal block')
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
  for(const [id,count] of [['shield',1],['lantern',16],['soul_lantern',16],['soul_torch',16],['stone',16],['diamond_sword',1],['apple',4],['water_bucket',1],['written_book',1]]) await transferRoundTrip(id,count)
  const output=process.env.TEST_RESULT || path.resolve(__dirname,'../../.integration/results.json')
  fs.writeFileSync(output,JSON.stringify({timestamp:new Date().toISOString(),bedrockVersion:'1.26.51',locale,checks,limitations:['Headless packet tests do not render or automate the Bedrock inventory UI.','Retail inventory drag behavior and attachable rendering require a retail client test.']},null,2)+'\n')
}
main().catch(error=>{console.error(error);process.exitCode=1}).finally(async()=>{
  state.closing=true
  if(inputTimer)clearInterval(inputTimer)
  if(client)client.close()
  if(rcon)rcon.close()
  await pause(500)
})
