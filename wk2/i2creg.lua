require "cord"
local REG = {}

-- Create a new I2C register binding
function REG:new(port, address)
    obj = {port=port, address=address}
    setmetatable(obj, self)
    self.__index = self
    return obj
end

-- Read a given register address
function REG:r(reg)
    -- TODO:
    -- create array with address
    -- write address
    -- read register with RSTART
    -- check all return values
    local arr = storm.array.create(1, storm.array.UINT8)
    arr:set(1, reg)

    local rv1 = cord.await(storm.i2c.write, self.port + self.address, storm.i2c.START, arr)
    if rv1 ~= storm.i2c.OK then return nil end
    local rv2 = cord.await(storm.i2c.read, self.port + self.address, storm.i2c.RSTART + storm.i2c.STOP, arr)
    return arr:get(1)
end

function REG:w(reg, value)
    -- TODO:
    -- create array with address and value
    -- write
    -- check return value
    local arr = storm.array.create(2, storm.array.UINT8)
    arr:set(1, reg) arr:set(2, value)
    local rv = cord.await(storm.i2c.write, self.port + self.address, storm.i2c.START + storm.i2c.STOP, arr)

end

return REG